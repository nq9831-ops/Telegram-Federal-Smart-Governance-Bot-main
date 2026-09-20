package com.tg.heyisheng.bot.core.retention;

import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import com.tg.heyisheng.bot.core.membership.MemberJoinObservationRepository;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository;
import com.tg.heyisheng.bot.core.moderation.ReviewStatus;
import com.tg.heyisheng.bot.core.moderation.SensitiveTopicStrikeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 数据保留策略服务（模块十 §11.2）：报告超期数据，并在显式开启时清理。
 *
 * <p><b>两态设计（刻意的）</b>：{@link #report()} 只读、永远安全；{@link #purge()} 才真删，
 * 且受 {@code tgg.retention.enabled} 门控（默认关）。保留策略的本质是自动删除业务数据，
 * 把它做成「默认就会删」的东西是危险的——运维必须先看到会发生什么，再决定放行。
 *
 * <p><b>audit_log 永不清理</b>：审计「不可删」是上一波立的规矩（应用层无删除入口、数据层交部署侧）。
 * 保留策略若把它纳入就地自我否定，故这里<b>硬编码排除</b>，并在报告里显式列出它及其原因
 * ——让读到报告的人知道「不是忘了，是刻意不删」。
 */
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    /** 审计表的报告条目：永远不可清理。 */
    static final String AUDIT_TABLE = "audit_log";
    static final String AUDIT_REASON = "审计合规：不可删除（应用层无删除入口；数据层加固见部署清单）";

    /** 一条保留策略报告。 */
    public record Finding(String table, String rule, long expired, boolean purgeable, String note) {
    }

    private final ModerationReviewRepository reviewRepository;
    private final SensitiveTopicStrikeRepository strikeRepository;
    private final MemberJoinObservationRepository memberJoinRepository;
    private final RetentionProperties properties;
    private final Clock clock;
    /** 热读取源；静态模式为 {@code null}（回落到 {@link RetentionProperties}）。 */
    private final RuntimeConfigService config;

    /** 静态模式（单测 / 固定配置）。 */
    public RetentionService(ModerationReviewRepository reviewRepository,
                            SensitiveTopicStrikeRepository strikeRepository,
                            MemberJoinObservationRepository memberJoinRepository,
                            RetentionProperties properties,
                            Clock clock) {
        this(reviewRepository, strikeRepository, memberJoinRepository, properties, clock, null);
    }

    /** 热模式：保留天数与开关在**调用期**读取，改配置无需重启。 */
    public RetentionService(ModerationReviewRepository reviewRepository,
                            SensitiveTopicStrikeRepository strikeRepository,
                            MemberJoinObservationRepository memberJoinRepository,
                            RetentionProperties properties,
                            Clock clock,
                            RuntimeConfigService config) {
        this.reviewRepository = reviewRepository;
        this.strikeRepository = strikeRepository;
        this.memberJoinRepository = memberJoinRepository;
        this.properties = properties;
        this.clock = clock;
        this.config = config;
    }

    private int reviewedQueueDays() {
        return days("tgg.retention.reviewed-queue-days", properties.getReviewedQueueDays());
    }

    private int strikeDays() {
        return days("tgg.retention.strike-days", properties.getStrikeDays());
    }

    private int membershipDays() {
        return days("tgg.retention.membership-days", properties.getMembershipDays());
    }

    private boolean enabled() {
        return config == null
                ? properties.isEnabled()
                : config.getBoolean("tgg.retention.enabled", properties.isEnabled());
    }

    private int days(String key, int fallback) {
        return config == null ? fallback : config.getInt(key, fallback);
    }

    /**
     * 只读报告：哪些数据已超期、各多少行。
     *
     * <p>永不删除任何东西——这是「先看后删」的第一步。
     */
    @Transactional(readOnly = true)
    public List<Finding> report() {
        Instant now = clock.instant();
        Instant queueCutoff = now.minus(Duration.ofDays(reviewedQueueDays()));
        Instant strikeCutoff = now.minus(Duration.ofDays(strikeDays()));
        Instant membershipCutoff = now.minus(Duration.ofDays(membershipDays()));

        long approved = reviewRepository.countByStatusAndCreatedAtBefore(ReviewStatus.APPROVED, queueCutoff);
        long rejected = reviewRepository.countByStatusAndCreatedAtBefore(ReviewStatus.REJECTED, queueCutoff);
        long strikes = strikeRepository.countByLastAtBefore(strikeCutoff);
        long memberships = memberJoinRepository.countByObservedAtBefore(membershipCutoff);

        return List.of(
                new Finding("moderation_review_queue",
                        "已裁决（APPROVED）且创建于 " + reviewedQueueDays() + " 天前",
                        approved, true, null),
                new Finding("moderation_review_queue",
                        "已裁决（REJECTED）且创建于 " + reviewedQueueDays() + " 天前",
                        rejected, true, null),
                new Finding("sensitive_topic_strikes",
                        "最近一次违规早于 " + strikeDays() + " 天前",
                        strikes, true, "清零后「屡犯升级」的累计重新开始（这是刻意的窗口语义）"),
                new Finding("member_join_observations",
                        "最后一次写入早于 " + membershipDays() + " 天前",
                        memberships, true, "入群观察数据：退群即删，此处是兜底（如 bot 已被移出群、收不到退群事件）"),
                new Finding(AUDIT_TABLE, "不适用（永不清理）", -1, false, AUDIT_REASON));
    }

    /**
     * 执行清理（仅在 {@code tgg.retention.enabled=true} 时）。
     *
     * @return 本次删除的总行数；未启用时返回 {@code -1}（区别于「启用但无可删」的 0）
     */
    @Transactional
    public long purge() {
        if (!enabled()) {
            log.info("保留策略未启用（tgg.retention.enabled=false）——本次只报告不清理");
            return -1;
        }
        Instant now = clock.instant();
        Instant queueCutoff = now.minus(Duration.ofDays(reviewedQueueDays()));
        Instant strikeCutoff = now.minus(Duration.ofDays(strikeDays()));
        Instant membershipCutoff = now.minus(Duration.ofDays(membershipDays()));

        long deleted = reviewRepository.deleteByStatusAndCreatedAtBefore(ReviewStatus.APPROVED, queueCutoff)
                + reviewRepository.deleteByStatusAndCreatedAtBefore(ReviewStatus.REJECTED, queueCutoff)
                + strikeRepository.deleteByLastAtBefore(strikeCutoff)
                + memberJoinRepository.deleteByObservedAtBefore(membershipCutoff);

        log.info("保留策略已清理过期数据：共 {} 行（审计表不在清理范围）", deleted);
        return deleted;
    }

}

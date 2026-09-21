package com.tg.heyisheng.bot.core.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.ChatPermissions;
import com.tg.heyisheng.bot.core.notify.Notification;
import com.tg.heyisheng.bot.core.notify.NotificationDispatcher;
import com.tg.heyisheng.bot.core.notify.NotificationLevel;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * 敏感话题的**检测 + 递进处置**编排（模块九 §10.5）。
 *
 * <p><b>V5.0 原文的处置是按「用户累计次数」递进</b>（不是按话题等级）：
 * 首次 → 删除 + 警告；二次 → 禁言 24h；三次及以上 → 联邦标记。<b>屡犯升级</b>即由此得到。
 *
 * <p><b>删除不在这里做</b>：本类返回 {@link Outcome#verdict()}，由 {@code UpdateDispatcher} 的
 * {@code ModerationEnforcer} 统一「命中即删」保底——那才是 webhook 唯一能立刻止损的返回值通道。
 * 本类只负责**额外**的主动动作（群内警告 / 禁言），它们走 {@link ModerationActionSender}。
 *
 * <p><b>「联邦标记」的边界（重要）</b>：{@code tgg-core} 处于依赖链最底层，**物理上看不到**
 * {@code tgg-federation} 的广播入口（{@code FederationBroadcaster.publish(CreditPenaltyOrder)}）。
 * 故模块九侧只做到「计数达到第 3 档并留痕」，真正的联邦广播需由模块七（信用事件 → 处罚令）与
 * 模块八（处罚令 → 广播）联动——与 {@code CreditEventSink} 的接缝用法一致。
 */
public class SensitiveTopicGuard {

    private static final Logger log = LoggerFactory.getLogger(SensitiveTopicGuard.class);

    /** 二次犯起禁言时长。 */
    static final Duration MUTE_DURATION = Duration.ofHours(24);
    /** 达到该累计次数即进入「联邦标记」档（跨模块可见：分发层据此发 `federationReport` 事件）。 */
    public static final int FEDERATION_STRIKE = 3;

    /** 一轮处置的结果：判定 + 该用户累计次数 + 是否已禁言。 */
    public record Outcome(ModerationVerdict verdict, int strike, boolean muted) {
    }

    private final SensitiveTopicDetector detector;
    private final SensitiveTopicStrikeService strikes;
    private final ModerationActionSender actionSender;
    /** 模块十的通知出口；为 null 表示未装配（此时不通知，但处置照常）。 */
    private final NotificationDispatcher notifications;
    private final Clock clock;

    public SensitiveTopicGuard(SensitiveTopicDetector detector,
                               SensitiveTopicStrikeService strikes,
                               ModerationActionSender actionSender,
                               NotificationDispatcher notifications) {
        this(detector, strikes, actionSender, notifications, Clock.systemUTC());
    }

    SensitiveTopicGuard(SensitiveTopicDetector detector,
                        SensitiveTopicStrikeService strikes,
                        ModerationActionSender actionSender,
                        NotificationDispatcher notifications,
                        Clock clock) {
        this.detector = detector;
        this.strikes = strikes;
        this.actionSender = actionSender == null ? ModerationActionSender.noop() : actionSender;
        this.notifications = notifications;
        this.clock = clock;
    }

    /**
     * 检测本群内容并按累计次数执行递进处置。
     *
     * @return 未命中（或被标签豁免）返回空；命中则给出判定与本次档位
     */
    public Optional<Outcome> handle(long chatId, Long userId, String content) {
        Optional<ModerationVerdict> hit = detector.inspect(chatId, content);
        if (hit.isEmpty()) {
            return Optional.empty();
        }
        // 无 userId（服务类更新）时不计数、不针对个人处置——但仍返回判定，
        // 让 enforcer 照常删除该条内容；静默跳过比「按陌生人处置」安全。
        if (userId == null) {
            return Optional.of(new Outcome(hit.get(), 0, false));
        }

        int strike = strikes.record(chatId, userId);
        boolean muted = enforce(chatId, userId, strike);
        ModerationVerdict escalated = escalate(hit.get(), strike);
        log.info("敏感话题递进处置：strike={} muted={} level={} rules={}",
                strike, muted, escalated.riskLevel(), escalated.matchedRuleIds());
        return Optional.of(new Outcome(escalated, strike, muted));
    }

    /**
     * 按累计次数<b>提升严重度</b>——原文 §10.5 的扣分是 5/15/30，而模块七恰好按
     * LOW/MEDIUM/HIGH 扣 5/15/30（见 {@code BuiltInCreditRules}），故把次数映射到等级即可对齐扣分。
     *
     * <p>取「话题等级」与「次数档位」的<b>较高者</b>：恐怖活动第一次就该是 HIGH，
     * 不该因为「初犯」被降级为 LOW。
     */
    private static ModerationVerdict escalate(ModerationVerdict verdict, int strike) {
        RiskLevel strikeLevel = strike >= FEDERATION_STRIKE
                ? RiskLevel.HIGH
                : (strike == 2 ? RiskLevel.MEDIUM : RiskLevel.LOW);
        RiskLevel level = strikeLevel.severity() > verdict.riskLevel().severity()
                ? strikeLevel
                : verdict.riskLevel();
        // hardLine 恒为 false：敏感话题是分级，不因次数变成红线。
        return new ModerationVerdict(level, false, verdict.matchedRuleIds());
    }

    /**
     * @return 是否已执行禁言
     */
    private boolean enforce(long chatId, Long userId, int strike) {
        if (strike < 2) {
            // 首次：群内警告（可观察，且给出「再犯将禁言」的明确预期）
            actionSender.send(SendMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .text(ModerationMessages.SENSITIVE_TOPIC_WARNING)
                    .build());
            return false;
        }

        actionSender.send(RestrictChatMember.builder()
                .chatId(chatId)
                .userId(userId)
                .permissions(ChatPermissions.builder().build())
                .untilDate((int) clock.instant().plus(MUTE_DURATION).getEpochSecond())
                .useIndependentChatPermissions(true)
                .build());

        if (strike >= FEDERATION_STRIKE) {
            // 模块九侧到此为止：联邦广播需模块七/八联动（见类 javadoc）。留痕以便运维与后续接线核对。
            log.warn("敏感话题累计 {} 次（已达联邦标记档）——实际广播待模块七/八联动接入", strike);
        }

        // 模块十：把「你被禁言了」告诉本人——权益变动必须让当事人知情，
        // 否则冤处置会在无人知晓的情况下持续生效。通知只带结论与次数，**不含消息正文**。
        if (notifications != null) {
            notifications.notify(new Notification(NotificationLevel.IMPORTANT, userId,
                    ModerationMessages.sensitiveTopicMuted(strike)));
        }
        return true;
    }
}

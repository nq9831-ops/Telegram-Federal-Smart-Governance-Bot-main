package com.tg.heyisheng.bot.admin.approval;

import com.tg.heyisheng.bot.core.moderation.ModerationReviewItem;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository;
import com.tg.heyisheng.bot.core.moderation.ReviewStatus;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 审批中心的<b>查询侧</b>（模块十一 §12.1）——待办列表、详情与统计。
 *
 * <p><b>本波只有「复核队列」一个来源</b>：原文 §12.1 的优先级表列了四项
 * （硬性红线 / 大额扣分 / 豁免申请 / 争议仲裁），但后三项在本项目<b>没有数据源</b>——
 * 「大额扣分」是模块七的自动动作（无需审批）、「豁免申请」不存在（§10.5 的群标签是声明即生效）、
 * 「争议仲裁」属模块十二。故此处<b>不伪造数据源</b>，也不为此预留空接口
 * （接口留了接缝 ≠ 真有替代实现），
 * 多来源扩展记为后续工作。
 *
 * <p><b>排序为何在应用层</b>：{@code risk_level} 以枚举名（LOW/MEDIUM/HIGH）存储，
 * 按字符串排序会得到 MEDIUM &gt; LOW &gt; HIGH 的错误次序。故取回后按 {@code severity()} 组装复合键。
 * 队列规模由保留策略兜底（超期条目会被清理），全量取回再排序是可接受的。
 */
public class ApprovalQueryService {

    /**
     * 优先级比较器——§12.1「硬性红线 &gt; 大额扣分 &gt; 豁免申请 &gt; 争议仲裁」在本项目的可落地映射。
     *
     * <p>三键依次是：① 硬红线优先（命中即已自动封禁，最该先看）；② 风险等级降序；
     * ③ 入队时间升序（同级先入先审——没有它，低等级条目会被源源不断的高等级条目<b>饿死</b>）。
     */
    static final Comparator<ModerationReviewItem> PRIORITY =
            Comparator.comparing(ModerationReviewItem::isHardLine).reversed()
                    // 取负实现降序：severity() 是小整数，不存在溢出风险
                    .thenComparingInt(item -> -item.getRiskLevel().severity())
                    .thenComparing(ModerationReviewItem::getCreatedAt);

    private final ModerationReviewRepository repository;
    private final Clock clock;
    private final com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService config;

    public ApprovalQueryService(ModerationReviewRepository repository,
                                Clock clock,
                                com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService config) {
        this.repository = repository;
        this.clock = clock;
        this.config = config;
    }

    /**
     * 超时阈值——**在调用期读取**（而非构造期捕获），故后台改配置后**无需重启**即生效。
     * 这是「热参数」的定义点：消费方每次用都现读 {@link RuntimeConfigService}。
     */
    private Duration remindAfter() {
        return config.getHours("tgg.admin.overdue-remind-hours", 24);
    }

    private Duration escalateAfter() {
        return config.getHours("tgg.admin.overdue-escalate-hours", 72);
    }

    /** 按状态取一页（{@code PENDING} 按优先级；已裁决的按时间倒序，最近裁决在前）。 */
    @Transactional(readOnly = true)
    public Page list(ReviewStatus status, int page, int size) {
        List<ModerationReviewItem> items =
                new ArrayList<>(repository.findByStatusOrderByIdAsc(status));
        if (status == ReviewStatus.PENDING) {
            items.sort(PRIORITY);
        } else {
            items.sort(Comparator.comparing(ModerationReviewItem::getCreatedAt).reversed());
        }

        int total = items.size();
        long offset = (long) Math.max(0, page) * Math.max(1, size);
        int from = (int) Math.min(offset, total);
        int to = (int) Math.min(offset + Math.max(1, size), total);
        List<Item> views = items.subList(from, to).stream().map(this::toItem).toList();
        return new Page(views, total, page, size);
    }

    /** 单条详情；不存在返回空（端点据此给出 404，而不是被异常处理器吞成 200）。 */
    @Transactional(readOnly = true)
    public Optional<Item> find(long id) {
        return repository.findById(id).map(this::toItem);
    }

    /**
     * 审批统计（§12.2 第 6 项）。
     *
     * <p>{@code avgDecisionHours} 为 {@code null} 表示<b>尚无已裁决条目</b>——
     * 刻意不用 0 代替：0 会被读成「秒批」，与「还没有数据」是两回事。
     */
    @Transactional(readOnly = true)
    public Stats stats() {
        long pending = repository.countByStatus(ReviewStatus.PENDING);
        long approved = repository.countByStatus(ReviewStatus.APPROVED);
        long rejected = repository.countByStatus(ReviewStatus.REJECTED);
        long hardLinePending = repository.countByHardLineTrueAndStatus(ReviewStatus.PENDING);

        Instant now = clock.instant();
        Duration remind = remindAfter();
        Duration escalate = escalateAfter();
        long overdueRemind = 0;
        long overdueEscalate = 0;
        for (ModerationReviewItem item : repository.findByStatusOrderByIdAsc(ReviewStatus.PENDING)) {
            Duration age = Duration.between(item.getCreatedAt(), now);
            if (age.compareTo(escalate) >= 0) {
                overdueEscalate++;
                overdueRemind++;   // 已升级的必然也超过了提醒阈值，两者不是互斥计数
            } else if (age.compareTo(remind) >= 0) {
                overdueRemind++;
            }
        }

        Double avgSeconds = repository.averageDecisionSeconds();
        Double avgHours = avgSeconds == null ? null : avgSeconds / 3600.0;
        return new Stats(pending, approved, rejected, hardLinePending,
                overdueRemind, overdueEscalate, avgHours);
    }

    private Item toItem(ModerationReviewItem item) {
        Duration age = Duration.between(item.getCreatedAt(), clock.instant());
        return new Item(item.getId(), item.getChatId(), item.getUserId(), item.getRuleIds(),
                item.getRiskLevel(), item.isHardLine(), item.getStatus(), item.getCreatedAt(),
                item.getDecidedAt(), item.getDecidedBy(), item.getNote(),
                age.toHours(), item.isPending() && age.compareTo(remindAfter()) >= 0);
    }

    /** 列表项（含派生字段 {@code ageHours} / {@code overdue}，省得每个消费方自己再算一遍时间）。 */
    public record Item(long id, Long chatId, Long userId, String ruleIds, RiskLevel riskLevel,
                       boolean hardLine, ReviewStatus status, Instant createdAt,
                       Instant decidedAt, Long decidedBy, String note,
                       long ageHours, boolean overdue) {
    }

    /** 一页结果。 */
    public record Page(List<Item> items, long total, int page, int size) {
    }

    /** 统计结果。 */
    public record Stats(long pending, long approved, long rejected, long hardLinePending,
                        long overdueRemind, long overdueEscalate, Double avgDecisionHours) {
    }
}

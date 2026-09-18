package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.core.notify.Notification;
import com.tg.heyisheng.bot.core.notify.NotificationDispatcher;
import com.tg.heyisheng.bot.core.notify.NotificationLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 红线复核 SLA 催办（模块九 §10.6「联邦快速复核：2 小时内人工确认」）。
 *
 * <p><b>为什么不另造一套复核</b>：§10.4 的推翻权已经建成「命中即入队（含硬红线）→ 操作员裁决
 * → 推翻则解封」这条链；§10.6 缺的<b>只是时间约束</b>。再造一套会产生两个并行的裁决入口，
 * 正是本项目反复警惕的分裂（判据与动作各写一遍必然漂移）。
 *
 * <p><b>只催办，不自动动作</b>：原文对「2 小时内未确认会怎样」<b>未作规定</b>。
 * 自动解封会让红线形同虚设、自动升级又无定义——故此处只把问题升级到人面前，由人决定。
 * 这是刻意的：规格没写的地方不替规格做决定。
 *
 * <p>催办走<b>紧急级</b>通知（不受用户免打扰阻塞），对象是平台白名单（与 {@code /review_*} 同一套）。
 */
public class RedLineReviewSla {

    private static final Logger log = LoggerFactory.getLogger(RedLineReviewSla.class);

    private final ModerationReviewRepository repository;
    private final ModerationReviewGuard guard;
    private final NotificationDispatcher notifications;
    private final Clock clock;
    private final Duration sla;

    public RedLineReviewSla(ModerationReviewRepository repository,
                            ModerationReviewGuard guard,
                            NotificationDispatcher notifications,
                            Clock clock,
                            Duration sla) {
        this.repository = repository;
        this.guard = guard;
        this.notifications = notifications;
        this.clock = clock;
        this.sla = sla;
    }

    /** 扫描超出 SLA 仍未裁决的硬红线条目并催办（每小时的 5 分执行）。 */
    @Scheduled(cron = "${tgg.moderation.redline-sla-cron:0 5 * * * *}")
    public void alarm() {
        Instant cutoff = clock.instant().minus(sla);
        // 入口无条件留痕：任务「没被注册」与「跑了但没数据」是完全不同的故障，
        // 没有这行日志二者在外部表现一致（都是静默）——上一轮就因此无法定案。
        log.info("红线复核 SLA 扫描：cutoff={}", cutoff);
        List<ModerationReviewItem> overdue = repository
                .findByHardLineTrueAndStatusAndCreatedAtBefore(ReviewStatus.PENDING, cutoff);
        log.info("红线复核 SLA 命中：{} 条", overdue.size());
        if (overdue.isEmpty()) {
            return;
        }
        for (ModerationReviewItem item : overdue) {
            String message = "⚠️ 硬红线复核超时（SLA " + sla.toHours() + " 小时）：复核 #"
                    + item.getId() + " 仍未裁决（规则 " + item.getRuleIds() + "）。"
                    + "请在 /review_list 中裁决：/review_approve 维持 或 /review_reject 推翻并解封。";
            log.warn("红线复核超时：id={} rule={} level={}",
                    item.getId(), item.getRuleIds(), item.getRiskLevel());
            for (Long reviewerId : guard.reviewerIds()) {
                notifications.notify(new Notification(NotificationLevel.URGENT, reviewerId, message));
            }
        }
    }
}

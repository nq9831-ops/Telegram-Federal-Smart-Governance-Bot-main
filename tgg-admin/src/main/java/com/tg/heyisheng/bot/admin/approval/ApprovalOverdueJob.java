package com.tg.heyisheng.bot.admin.approval;

import com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard;
import com.tg.heyisheng.bot.core.moderation.ReviewStatus;
import com.tg.heyisheng.bot.core.notify.Notification;
import com.tg.heyisheng.bot.core.notify.NotificationDispatcher;
import com.tg.heyisheng.bot.core.notify.NotificationLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Set;

/**
 * 审批超时提醒（模块十一 §12.1 第 4 步）：超过 {@code remind} 小时未处理 → 提醒；
 * 超过 {@code escalate} 小时 → 升级提醒。催办对象是与 {@code /review_*} 同一套的平台白名单。
 *
 * <p><b>为什么跳过硬红线</b>：§10.6 已有一条<b>更严</b>的通道（硬红线 2 小时 SLA 催办）。
 * 若这里再按 24h/72h 催一遍，同一件事会被催两次——运营者很快会把这类提醒整体静音，
 * 那才是真正的损失。故本条只负责<b>非硬红线</b>的积压。
 *
 * <p><b>入口无条件留痕</b>：沿用 §10.6 用真实代价换来的教训——
 * 「任务没被调度」与「调度了但一条都没命中」在外部表现完全相同（都是静默），
 * 没有入口日志就无法定案。
 *
 * <p><b>只提醒、不自动处置</b>：审批是人的判断，超时的正确处置是让人看见，而不是替人做决定。
 */
public class ApprovalOverdueJob {

    private static final Logger log = LoggerFactory.getLogger(ApprovalOverdueJob.class);

    private final ApprovalQueryService queries;
    private final ModerationReviewGuard guard;
    private final NotificationDispatcher notifications;
    private final long remindHours;
    private final long escalateHours;

    public ApprovalOverdueJob(ApprovalQueryService queries,
                              ModerationReviewGuard guard,
                              NotificationDispatcher notifications,
                              long remindHours,
                              long escalateHours) {
        this.queries = queries;
        this.guard = guard;
        this.notifications = notifications;
        this.remindHours = remindHours;
        this.escalateHours = escalateHours;
    }

    @Scheduled(cron = "${tgg.admin.overdue-cron:0 15 * * * *}")
    public void run() {
        List<ApprovalQueryService.Item> pending =
                queries.list(ReviewStatus.PENDING, 0, Integer.MAX_VALUE).items();

        log.info("审批超时扫描：待办 {} 条（提醒阈值 {}h / 升级阈值 {}h）",
                pending.size(), remindHours, escalateHours);

        List<ApprovalQueryService.Item> overdue = pending.stream()
                .filter(item -> !item.hardLine())
                .filter(item -> item.ageHours() >= remindHours)
                .toList();

        log.info("审批超时命中：{} 条（已排除硬红线——它们由 §10.6 的 2h SLA 通道负责）", overdue.size());
        if (overdue.isEmpty()) {
            return;
        }

        Set<Long> reviewers = guard.reviewerIds();
        if (reviewers.isEmpty()) {
            // 配了功能却没人可催：必须显式告警，否则它会以「一切正常」的样子静默失效
            log.warn("审批超时：复核人白名单为空，无处催办（请配置 TGG_MODERATION_REVIEWERS）");
            return;
        }

        for (ApprovalQueryService.Item item : overdue) {
            boolean escalated = item.ageHours() >= escalateHours;
            String message = (escalated ? "⚠️ 审批升级" : "审批提醒")
                    + "：待办 #" + item.id()
                    + "（等级 " + item.riskLevel() + "，已积压 " + item.ageHours() + " 小时）"
                    + " 仍未处理，请在 GET /admin/approvals 中裁决。";
            for (Long reviewerId : reviewers) {
                notifications.notify(new Notification(NotificationLevel.URGENT, reviewerId, message));
            }
        }
    }
}

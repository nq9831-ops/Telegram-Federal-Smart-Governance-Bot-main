package com.tg.heyisheng.bot.core.breach;

import com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard;
import com.tg.heyisheng.bot.core.notify.Notification;
import com.tg.heyisheng.bot.core.notify.NotificationDispatcher;
import com.tg.heyisheng.bot.core.notify.NotificationLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

/**
 * 泄露通报催办任务（模块十 §11.2）——未在 72 小时内通报的事件，每小时催一次。
 *
 * <p><b>用紧急级通知</b>：这是合规事故，不能被用户的免打扰时段压住（模块十的紧急级不受免打扰限制）
 * ——压到第二天早上才提醒，等于把 72 小时的窗口白白烧掉。
 *
 * <p><b>催办对象是「平台白名单」</b>：与 {@code /review_*} 同一套（{@link ModerationReviewGuard}）。
 * 复用而不新造第四套白名单，是因为「谁能处置平台级事故」这个问题在本项目已有唯一答案。
 */
public class DataBreachJob {

    private static final Logger log = LoggerFactory.getLogger(DataBreachJob.class);

    private final DataBreachService service;
    private final ModerationReviewGuard guard;
    private final NotificationDispatcher notifications;

    public DataBreachJob(DataBreachService service,
                         ModerationReviewGuard guard,
                         NotificationDispatcher notifications) {
        this.service = service;
        this.guard = guard;
        this.notifications = notifications;
    }

    @Scheduled(cron = "${tgg.breach.remind-cron:0 0 * * * *}")
    public void remind() {
        List<DataBreachIncident> due = service.dueForReminder();
        if (due.isEmpty()) {
            return;
        }
        for (DataBreachIncident incident : due) {
            String message = BreachMessages.reminder(incident.getId(), incident.getScope(),
                    incident.getAffectedCount(), incident.getDeadlineAt());
            log.warn("数据泄露催办：id={} 截止={} 已逾期={}", incident.getId(), incident.getDeadlineAt(),
                    incident.isOverdue(java.time.Instant.now()));
            for (Long reviewerId : guard.reviewerIds()) {
                notifications.notify(new Notification(NotificationLevel.URGENT, reviewerId, message));
            }
        }
    }

}

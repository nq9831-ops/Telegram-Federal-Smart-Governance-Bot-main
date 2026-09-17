package com.tg.heyisheng.bot.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalTime;

/**
 * 通知分发（模块十 §11.1）：<b>判级别 → 免打扰 → 频率门 → 投递 / 暂存 / 合并为摘要</b>。
 *
 * <p>顺序是刻意的：<b>免打扰先于频率门</b>。若先过频率门再判定静默，被暂存的那些通知会白白吃掉
 * 本小时额度——用户睡了 8 小时醒来，额度已用完、一条都收不到。
 *
 * <p><b>被抑制 ≠ 丢失</b>：超限的通知不投递，但计数被累计，下一次额度可用时以
 * 「（另有 N 条同类通知已合并）」附在正文后；处于免打扰时段的则<b>落库暂存</b>，
 * 由 {@code DeferredNotificationFlusher} 在时段结束后冲刷——两条路径都不静默丢弃。
 */
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationSender sender;
    private final NotificationRateLimiter limiter;
    /** 免打扰偏好；为 null 表示不启用免打扰（此时只做频率门）。 */
    private final NotificationPreferenceService preferences;
    /** 暂存队列；为 null 表示不启用暂存（与 preferences 同生共死）。 */
    private final DeferredNotificationRepository deferred;
    private final Clock clock;

    /** 基础构造：不启用免打扰（只做频率门）。 */
    public NotificationDispatcher(NotificationSender sender, NotificationRateLimiter limiter) {
        this(sender, limiter, null, null, Clock.systemUTC());
    }

    public NotificationDispatcher(NotificationSender sender,
                                  NotificationRateLimiter limiter,
                                  NotificationPreferenceService preferences,
                                  DeferredNotificationRepository deferred,
                                  Clock clock) {
        this.sender = sender == null ? NotificationSender.logging() : sender;
        this.limiter = limiter;
        this.preferences = preferences;
        this.deferred = deferred;
        this.clock = clock;
    }

    /**
     * 分发一条通知。
     *
     * @return 本次是否真的投递（false = 已暂存待时段结束，或被频率门抑制并入摘要）
     */
    public boolean notify(Notification notification) {
        if (shouldDefer(notification)) {
            deferred.save(new DeferredNotification(notification.recipientId(), notification.level(),
                    notification.text(), clock.instant()));
            log.info("处于免打扰时段，通知已暂存待时段结束发送：level={} recipient={}",
                    notification.level(), notification.recipientId());
            return false;
        }

        NotificationRateLimiter.Decision decision =
                limiter.decide(notification.level(), notification.recipientId());
        if (!decision.allowed()) {
            log.info("通知超限，已并入摘要：level={} recipient={}",
                    notification.level(), notification.recipientId());
            return false;
        }

        String text = decision.mergedCount() > 0
                ? notification.text() + "\n（另有 " + decision.mergedCount() + " 条同类通知已合并）"
                : notification.text();
        sender.send(notification.recipientId(), text);
        return true;
    }

    /**
     * 是否应暂存而非立即发送。
     *
     * <p><b>紧急通知不受免打扰限制</b>（原文：紧急的封禁/解封/信用分恢复必须立刻送达）——
     * 把「你被解封了」压到早上八点，等于让误封多持续一夜。
     */
    private boolean shouldDefer(Notification notification) {
        if (preferences == null || deferred == null) {
            return false;
        }
        if (notification.level() == NotificationLevel.URGENT) {
            return false;
        }
        return preferences.isQuietAt(notification.recipientId(), LocalTime.now(clock));
    }
}

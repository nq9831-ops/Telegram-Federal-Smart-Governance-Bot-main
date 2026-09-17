package com.tg.heyisheng.bot.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通知分发（模块十 §11.1）：<b>判级别 → 过频率门 → 投递或被合并为摘要</b>。
 *
 * <p>三级分类的额度由 {@link NotificationLevel} 承载，窗口计数由 {@link NotificationRateLimiter}
 * 承担——本类只做「按判定结果投递」这一件事，保持可测且无状态。
 *
 * <p><b>被抑制 ≠ 丢失</b>：超限的通知不投递，但计数被累计，下一次额度可用时以
 * 「（另有 N 条同类通知已合并）」附在正文后——这是原文「超限时合并为摘要发送」的落点。
 */
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationSender sender;
    private final NotificationRateLimiter limiter;

    public NotificationDispatcher(NotificationSender sender, NotificationRateLimiter limiter) {
        this.sender = sender == null ? NotificationSender.logging() : sender;
        this.limiter = limiter;
    }

    /**
     * 分发一条通知。
     *
     * @return 本次是否真的投递（false = 被频率门抑制、已并入摘要）
     */
    public boolean notify(Notification notification) {
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
}

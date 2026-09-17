package com.tg.heyisheng.bot.core.notify;

import java.util.Objects;

/**
 * 一条待投递的通知（模块十 §11.1）。
 *
 * <p><b>刻意不含消息正文</b>：与 {@code ModerationVerdict} / {@code CreditEvent} 同款纪律——
 * 通知文本由<b>生产侧</b>按结论拼装（如「你在群 X 被禁言」），绝不把被判定内容原样带进来，
 * 否则正文会经通知这条侧路泄露到私聊与日志。
 *
 * @param level       通知级别（决定频率额度）
 * @param recipientId 收件人 userId（私聊投递）
 * @param text        通知正文（由生产侧拼装，不含用户消息原文）
 */
public record Notification(NotificationLevel level, long recipientId, String text) {

    public Notification {
        Objects.requireNonNull(level, "level 不可为空");
        Objects.requireNonNull(text, "text 不可为空");
    }
}

package com.tg.heyisheng.bot.core.notify;

import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * 私聊投递实现（模块十）：复用既有的 <b>主动通道</b> {@link ModerationActionSender} 发 {@code sendMessage}。
 *
 * <p><b>为什么复用主动通道而不是另建一条</b>：webhook 模式下一次更新只能回一个 Bot API 方法，
 * 「返回值之外的主动调用」早有一条经过验证的通道（硬红线封禁就是经它发出的）。
 * 它已有正确的降级语义：<b>配了 {@code TGG_BOT_TOKEN} 才真发，否则 noop 且启动期已 WARN</b>
 * ——这正是通知需要的取舍，不必重造。
 *
 * <p><b>前置条件</b>：Bot API 不允许主动私聊未与 bot 对话过的用户——收件人须曾与 bot 有过会话
 * （与 listing 的下架通知同一约束）。
 */
public class TelegramNotificationSender implements NotificationSender {

    private final ModerationActionSender actionSender;

    public TelegramNotificationSender(ModerationActionSender actionSender) {
        this.actionSender = actionSender;
    }

    @Override
    public void send(long recipientId, String text) {
        actionSender.send(SendMessage.builder()
                .chatId(String.valueOf(recipientId))
                .text(text)
                .build());
    }
}

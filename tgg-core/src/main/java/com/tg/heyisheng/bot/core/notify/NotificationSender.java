package com.tg.heyisheng.bot.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通知投递接缝（模块十）——与 listing 的 {@code SubmitterNotifier} 同款范式：
 * <b>接口 + 默认实现</b>，让「通知」不强依赖某个具体通道。
 *
 * <p><b>为什么是接缝而不是直接调 Bot API</b>：本项目已有教训——「接口留了可替换接缝」不等于
 * 「真有替代实现」。故这里有<b>两个</b>实现：{@link #logging()}（默认，无 token 时的显式降级）
 * 与 {@link TelegramNotificationSender}（有 token 时真投递）。判据是 `implements NotificationSender`
 * 的实现个数，而不是接口注释里的承诺。
 *
 * <p><b>契约</b>：实现须自行吞掉异常（失败只记日志）——通知是增强，不得中断业务主链路。
 */
@FunctionalInterface
public interface NotificationSender {

    /**
     * 投递一条通知。
     *
     * @param recipientId 收件人 userId
     * @param text        通知正文
     */
    void send(long recipientId, String text);

    /** 默认实现：只记日志（未配置 bot token 时的显式降级，不是静默丢弃）。 */
    static NotificationSender logging() {
        Logger log = LoggerFactory.getLogger(NotificationSender.class);
        return (recipientId, text) -> log.info("通知（未配置投递通道，仅日志）：recipient={} text={}",
                recipientId, text);
    }
}

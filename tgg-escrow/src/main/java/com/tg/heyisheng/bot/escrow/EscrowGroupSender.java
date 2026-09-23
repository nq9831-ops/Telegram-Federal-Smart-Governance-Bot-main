package com.tg.heyisheng.bot.escrow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 群内通知通道：把交易进度<b>主动发到</b>交易所在群（区别于"回复命令"的回执）。
 *
 * <p><b>为什么需要单独一条通道</b>：{@code NotificationSender}（core/notify）只做<b>私聊</b>
 * （{@code send(recipientId, text)}），而交易进度需要让对方与围观者在群里看得见——
 * 私聊确保送达，群内让流程公开可核。两者职责不同，故各有开关（见 {@code EscrowProperties.Notify}）。
 *
 * <p><b>契约</b>：实现须自行吞掉异常（失败只记日志）——与 {@code NotificationSender} 同款：
 * 通知是增强，不得中断资金主链路。
 *
 * <p><b>默认实现为显式降级</b>（只记日志），不是静默丢弃——与 {@code DepositGateway} / {@code NotificationSender}
 * 同一取舍：本机无法验证的真实投递不写进主线，由部署方提供实现。
 */
@FunctionalInterface
public interface EscrowGroupSender {

    /**
     * 向指定群发送一条通知。
     *
     * @param groupChatId 群 chatId（Telegram 群为负数）
     * @param text        正文（由生产侧拼装，不含任何用户消息原文）
     */
    void send(long groupChatId, String text);

    /** 默认实现：只记日志（未装配真实通道时的显式降级）。 */
    static EscrowGroupSender logging() {
        Logger log = LoggerFactory.getLogger(EscrowGroupSender.class);
        return (chatId, text) -> log.info("担保交易群内通知（未接真实通道）：chatId={} text={}", chatId, text);
    }
}

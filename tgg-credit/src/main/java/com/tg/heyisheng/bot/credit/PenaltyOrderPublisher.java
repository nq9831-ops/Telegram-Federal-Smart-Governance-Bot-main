package com.tg.heyisheng.bot.credit;

/**
 * 处罚令发布通道（模块七）。
 *
 * <p><b>默认空实现</b>：与 {@code CreditEventSink} / {@code ModerationActionSender} 同模式。
 * 本阶段发布为 noop——跨节点广播属模块八联邦治理；模块七只保证"产出并签名"这一环可用、可测。
 *
 * <p>实现须自行吞掉异常（失败只记日志）——广播失败不得回滚本地记账。
 */
@FunctionalInterface
public interface PenaltyOrderPublisher {

    void publish(CreditPenaltyOrder order);

    /** 空实现：未接入消费方（如模块八联邦）时不做任何广播。 */
    static PenaltyOrderPublisher noop() {
        return order -> {
        };
    }
}

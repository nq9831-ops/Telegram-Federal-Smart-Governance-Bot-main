package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.credit.CreditPenaltyOrder;

/**
 * 收到并接受一条联邦处罚令后的本地执行钩子。
 *
 * <p>与 {@code PenaltyOrderPublisher} 同模式：接口 + noop 默认，让入站接收链路不强依赖
 * "跨群封禁"这一具体动作（也便于单测入站逻辑时不真的去封人）。
 */
@FunctionalInterface
public interface FederationBanHandler {

    /**
     * 对已接受的处罚令执行本地动作（跨群封禁等）。
     *
     * <p>实现须自行吞掉异常——执行失败不得回滚"已落库"这一事实，
     * 也不得让入站请求失败（否则对端会认为没送达而重试）。
     */
    void onPenaltyAccepted(CreditPenaltyOrder order);

    static FederationBanHandler noop() {
        return order -> {
        };
    }
}

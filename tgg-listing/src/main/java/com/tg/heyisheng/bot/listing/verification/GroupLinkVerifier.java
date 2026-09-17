package com.tg.heyisheng.bot.listing.verification;

import com.tg.heyisheng.bot.listing.ListingGroup;

/**
 * 群链接探针（<b>可替换接缝</b>）。
 *
 * <p>「本机不可用」是已知事实：本机没有公网地址、没有真实 bot token，任何真实 Telegram 探测
 * 都无法验证。因此探针必须是一个接口——生产用 {@link TelegramGroupLinkVerifier}，
 * 测试用替身，部署方也可以换成自己可控的实现（例如经代理、或改用 {@code getChatMember}）。
 *
 * <p><b>契约（硬性）</b>：实现<b>不得</b>把探测层的问题表达为 {@link VerificationResult#FAIL}。
 * 网络不通、超时、缺 token、限流、5xx、响应体无法解析——一律 {@link VerificationResult#ERROR}。
 * 只有「Telegram 明确答复该群/链接已不存在」才可返回 {@code FAIL}。
 * 服务层另有一层兜底：实现抛出的异常会被折成 {@code ERROR}（见 {@code ListingGroupService#verify}）。
 */
@FunctionalInterface
public interface GroupLinkVerifier {

    /**
     * 探测一条收录条目是否仍然可达。
     *
     * @param entry 收录条目（用其 {@code chatId} / {@code inviteLink} 定位目标）
     * @return {@code OK} / {@code FAIL} / {@code ERROR}，语义见 {@link VerificationResult}
     */
    VerificationResult verify(ListingGroup entry);
}

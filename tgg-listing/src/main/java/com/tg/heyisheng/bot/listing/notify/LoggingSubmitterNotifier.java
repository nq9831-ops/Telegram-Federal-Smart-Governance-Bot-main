package com.tg.heyisheng.bot.listing.notify;

import com.tg.heyisheng.bot.listing.ListingGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 开发期默认通知实现：只记日志，<b>不真实投递</b>。
 *
 * <p><b>为什么默认是它</b>：本机没有可用的 bot 投递通路，「写一个发不出去的发送器」是
 * 本项目反复警惕的「接好了但没通电」。因此默认实现明确保持<b>只留痕</b>，
 * 并在每次调用打 WARN 说明「未真实投递」——运维不会误以为提交者已收到通知。
 * 真实投递（经 Bot API 私聊提交者 + 附申诉指引）由部署方实现 {@link SubmitterNotifier} 替换。
 *
 * <p><b>日志脱敏</b>：这里<b>只记条目 id</b>。不记 {@code userId} / {@code chatId} 明文
 * （用户/群标识属个人信息，V5.0 要求哈希化；本类干脆一个都不记，泄漏面为零），
 * 更不记邀请链接（{@code https://t.me/+...} 里的 token 等同入群凭证）。
 *
 * <p><b>异常契约</b>：本实现不抛异常（只有日志）。但它仍遵守接口契约的完整形态——
 * 未来替身/真实实现若抛出，调用方（{@code GroupLinkVerificationJob}）另有一层兜底。
 */
public class LoggingSubmitterNotifier implements SubmitterNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingSubmitterNotifier.class);

    @Override
    public void notifyDelisted(ListingGroup entry) {
        // 只记条目 id：足以让运维追溯到「哪一条被判失效」，且不含任何个人标识或凭证
        log.warn("收录条目 #{} 已失效下架：本通道为默认日志实现，未真实投递提交者；"
                + "部署方需装配真实通知通道（含申诉指引），否则提交者不会收到任何告知。",
                entry.getId());
    }
}

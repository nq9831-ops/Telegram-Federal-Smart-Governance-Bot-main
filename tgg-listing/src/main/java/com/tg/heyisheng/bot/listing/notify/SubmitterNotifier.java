package com.tg.heyisheng.bot.listing.notify;

import com.tg.heyisheng.bot.listing.ListingGroup;

/**
 * 下架通知通道（设计文档 §6.4）。
 *
 * <p><b>为什么是接口</b>：本机没有可用的 bot 投递通路（无公网、无真实 token），
 * 「通知提交者」在开发期只能是一个可替换接缝——生产接真实 Telegram 投递，
 * 测试接可捕获的替身（断言「真的被调用」，而不是断言日志里出现了某行字）。
 *
 * <p><b>契约（照 {@code ModerationActionSender}，硬性）</b>：实现<b>必须自行吞掉异常</b>
 * （失败只记日志）。理由：通知发生在「条目已被判失效并落库」之后，
 * 投递失败不能让下架结果回滚、也不能中断整轮验证任务——否则一个不可用的通知通道
 * 会连锁成「收录库永远清理不掉失效群」。
 *
 * <p>本接口<b>不</b>承担「是否该通知」的判定：只有状态机真的把条目转成 SUSPENDED 时才调用
 * （见 {@code GroupLinkVerificationJob}）。
 */
@FunctionalInterface
public interface SubmitterNotifier {

    /**
     * 通知提交者「其收录的群已失效下架」，并给出申诉指引。
     *
     * @param entry 已转 SUSPENDED 的收录条目（用其 id / chatId / submitterUserId 定位收件人）
     */
    void notifyDelisted(ListingGroup entry);

    /**
     * 空实现：未装配通知通道时至少不影响下架流程本身。
     *
     * <p>注意这是<b>兜底</b>而非正常路径——生产必须装配真实通道（{@code ListingConfiguration} 装配
     * {@code LoggingSubmitterNotifier} 作为开发期默认，真实投递归部署方）。装配方若退化为空实现，
     * 等于「下架了但没人知道」，须显式告警。
     */
    static SubmitterNotifier noop() {
        return entry -> {
        };
    }
}

package com.tg.heyisheng.bot.core.moderation;

import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;

/**
 * 在「handler 返回值」之外主动执行 Bot API 方法的通道。
 *
 * <p><b>存在理由</b>：webhook 模式下库把 handler 返回值作为 HTTP 响应体交回 Telegram，
 * 因此一次更新只能执行<b>一个</b>方法。硬红线处置需要「删除 + 封禁」两个动作——
 * 删除作为返回值保底同步执行，封禁这类额外动作必须走本通道主动调用。
 *
 * <p><b>为什么不复用 failover 的 {@code TelegramApiMethodExecutor}</b>：
 * 那个 bean 挂在 {@code tgg.failover.enabled=true} 下，默认不存在；
 * 硬红线冻结是安全关键功能，不能因一个默认关闭的开关而静默失效
 * （本项目已多次因「默认关闭即静默降级」吃亏，见 .rivet.md 与 docs/DEPLOYMENT-VERIFICATION.md）。
 *
 * <p><b>契约</b>：实现必须自行吞掉异常（失败只记日志）——单条处置动作失败
 * 不应中断整条 update 的处理，更不应影响作为响应体的删除动作。
 */
@FunctionalInterface
public interface ModerationActionSender {

    /**
     * 发送一个 Bot API 方法。
     *
     * @param method 待发送方法；实现应对 null 与发送失败做防御
     */
    void send(BotApiMethod<?> method);

    /**
     * 空实现：未装配发送通道时至少保证「删除」生效。
     *
     * <p>注意这是<b>兜底</b>而非正常路径——生产必须装配真实通道（见 TggCoreConfiguration），
     * 否则硬红线只删不冻。装配方若因缺 token 退化为空实现，须显式告警。
     */
    static ModerationActionSender noop() {
        return method -> {
        };
    }
}

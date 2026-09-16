package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.model.UpdateContext;

/**
 * 审核命中的「人工复核」消费者：把中高风险判定入队待人工确认。
 *
 * <p><b>存在理由</b>：判定结果挂到上下文后若无消费者，复核能力在功能上等于零——
 * 这正是本项目反复出现的「接好了但没通电」模式。
 *
 * <p><b>谁该入队</b>：{@code needsReview() && !shouldFreezeImmediately()}，
 * 即中高风险但非硬红线。硬红线走「立即删除 + 封禁」，不等复核；clean 无意义。
 *
 * <p><b>默认空实现</b>：与 {@link ModerationActionSender} 同模式——让
 * {@code UpdateDispatcher} 不强依赖数据库，未装配时审核主链路照常工作。
 */
@FunctionalInterface
public interface ModerationReviewRecorder {

    /**
     * 记录一条待复核的命中。
     *
     * <p>实现须自行吞掉异常（失败只记日志）——入队失败不得中断消息处理主链路。
     *
     * @param ctx     触发本次命中的上下文（提供 chatId / userId / messageId）
     * @param verdict 判定结果（提供规则 id 与风险等级；<b>不含正文</b>）
     */
    void record(UpdateContext ctx, ModerationVerdict verdict);

    /** 空实现：未装配队列时不做任何记录。 */
    static ModerationReviewRecorder noop() {
        return (ctx, verdict) -> {
        };
    }
}

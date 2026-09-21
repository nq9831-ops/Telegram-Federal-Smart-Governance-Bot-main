package com.tg.heyisheng.bot.core.credit;

/**
 * 信用事件发布通道（模块七）。
 *
 * <p><b>默认空实现</b>：与 {@code ModerationReviewRecorder} / {@code ModerationActionSender}
 * 同模式——让 {@code UpdateDispatcher} 不强依赖信用分模块；未装配信用分时，审核主链路照常工作。
 *
 * <p><b>实现契约</b>：实现须自行吞掉异常（失败只记日志）。
 * 发布信用事件失败<b>不得</b>中断消息处理主链路——信用分是增强，不是消息处理的必要环节。
 */
@FunctionalInterface
public interface CreditEventSink {

    /**
     * 发布一条信用事件。
     *
     * @param event 事件（不含消息正文）
     */
    void publish(CreditEvent event);

    /**
     * 反向补偿：按原事件的幂等键退回它<b>实际</b>扣掉的分（模块十一 · 推翻案件后）。
     *
     * <p><b>默认空实现</b>：未装配信用分（或为 {@link #noop()}）时无从退分，返回 {@code false}
     * ——与 {@link #publish} 同属"未接线即零影响"的取舍。
     *
     * <p><b>吞异常是契约</b>：与 {@link #publish} 一致。退分是裁决流程的旁挂环节，
     * 不能因它失败而让裁决本身回滚（状态已落库、解封动作已发出）。
     *
     * @param originalIdempotencyKey 原扣分事件的幂等键（{@code moderation:<chatId>:<messageId>}）
     * @param reversalIdempotencyKey 补偿自身的幂等键（须与原键不同）
     * @param reason                 说明（不含正文）
     * @return {@code true} = 本次真的退了分
     */
    default boolean reverse(String originalIdempotencyKey, String reversalIdempotencyKey, String reason) {
        return false;
    }

    /** 空实现：未装配信用分模块时不做任何发布。 */
    static CreditEventSink noop() {
        return event -> {
        };
    }
}

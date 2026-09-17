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

    /** 空实现：未装配信用分模块时不做任何发布。 */
    static CreditEventSink noop() {
        return event -> {
        };
    }
}

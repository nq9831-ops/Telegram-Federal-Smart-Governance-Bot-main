package com.tg.heyisheng.bot.core.interaction;

/**
 * 上下文标记：本次命令执行**已经过确认卡确认**。
 *
 * <p><b>它是「确认」的唯一凭据，且不可由外部请求伪造</b>：只有 {@link CallbackCommandBridge}
 * 在「nonce 已被成功消费」之后才会把它挂到 {@code UpdateContext} 上。文本命令哪怕带了参数，
 * 也挂不上这个标记——所以「带参数发一条命令」不构成确认。
 *
 * <p>刻意做成空标记类而非布尔字段：标记只能由持有本类的地方构造，语义无法被别处的赋值搞错。
 */
public final class ConfirmationGranted {
}

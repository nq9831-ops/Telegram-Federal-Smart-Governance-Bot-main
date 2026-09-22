package com.tg.heyisheng.bot.core.failover;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.core.dispatch.UpdateDispatcher;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 降级模式下回复发送成败的观测（GUARD-6）。
 *
 * <p>守的重点：装配处原先写的是 {@code dispatch(update).ifPresent(executor::execute)}，
 * 而 {@link TelegramApiMethodExecutor#execute} 返回 {@code boolean}——
 * {@code ifPresent} 把发送结果<b>整个丢掉</b>。于是降级期「消息收得到、回复发不出去」
 * 只在 executor 内部留一条 warn，聚合层面完全不可见。
 *
 * <p><b>失败用真实失败模式</b>：让发送器返回 {@code false}（而非抛异常）——
 * 这正是 {@code execute} 表达失败的方式，也是 {@code ifPresent} 会吞掉的那种结果。
 * 发送器接口保持返回 {@code boolean}，绝不改成 {@code Consumer}（那会再次丢掉成败）。
 */
class PollingReplySenderTest {

    private final UpdateDispatcher dispatcher = mock(UpdateDispatcher.class);
    private final Update update = mock(Update.class);

    private final BotApiMethod<?> reply = mock(BotApiMethod.class);

    /** 发送器子类固定返回给定结果——不触网，且忠实复刻 execute 的成败语义。 */
    private static TelegramApiMethodExecutor executorReturning(boolean result) {
        return new TelegramApiMethodExecutor(new OkHttpClient(), new ObjectMapper(), "123456:ABC-DEF") {
            @Override
            public boolean execute(BotApiMethod<?> method) {
                return result;
            }
        };
    }

    @Test
    void sendFailureIsReportedAndCounted() throws Exception {
        when(dispatcher.dispatch(update)).thenReturn(Optional.of(reply));
        PollingReplySender sender = new PollingReplySender(dispatcher, executorReturning(false));

        assertThat(sender.handleAndObserve(update)).as("有回复却发不出去，必须报告为失败").isTrue();
        assertThat(sender.sendFailureCount()).as("失败次数必须累计——否则降级期回复静默丢失").isEqualTo(1);
    }

    @Test
    void sendSuccessIsNotAFailure() throws Exception {
        when(dispatcher.dispatch(update)).thenReturn(Optional.of(reply));
        PollingReplySender sender = new PollingReplySender(dispatcher, executorReturning(true));

        assertThat(sender.handleAndObserve(update)).isFalse();
        assertThat(sender.sendFailureCount()).isZero();
    }

    @Test
    void noReplyIsNotAFailure() throws Exception {
        when(dispatcher.dispatch(update)).thenReturn(Optional.empty());
        PollingReplySender sender = new PollingReplySender(dispatcher, executorReturning(false));

        assertThat(sender.handleAndObserve(update)).isFalse();
        assertThat(sender.sendFailureCount()).isZero();
    }

    @Test
    void dispatchExceptionIsSwallowedAndNotCountedAsSendFailure() throws Exception {
        when(dispatcher.dispatch(update)).thenThrow(new RuntimeException("boom"));
        PollingReplySender sender = new PollingReplySender(dispatcher, executorReturning(false));

        assertThat(sender.handleAndObserve(update)).isFalse();
        assertThat(sender.sendFailureCount()).isZero();
    }
}

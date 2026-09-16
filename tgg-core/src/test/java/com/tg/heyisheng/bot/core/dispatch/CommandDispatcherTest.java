package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.exception.TggDispatchException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命令分发测试：输入是 {@link UpdateContext}（由 UpdateDispatcher 构造），
 * 故此处聚焦「上下文 → 处理器」的路由语义；Update → 上下文的提取见 {@link UpdateDispatcherTest}。
 */
class CommandDispatcherTest {

    private final CommandRegistry registry = new CommandRegistry(List.of(new EchoCommandHandler()));
    private final CommandDispatcher dispatcher = new CommandDispatcher(registry);

    @Test
    void routesKnownCommandAndProducesReply() throws Exception {
        Optional<BotApiMethod<?>> reply = dispatcher.dispatch(ctx("/echo"));

        assertThat(reply).isPresent();
        assertThat(reply.get()).isInstanceOf(SendMessage.class);
        assertThat(((SendMessage) reply.get()).getText()).isEqualTo(EchoCommandHandler.REPLY_TEXT);
    }

    @Test
    void routesCommandWithBotUsernameSuffix() throws Exception {
        assertThat(dispatcher.dispatch(ctx("/echo@MyGovernanceBot"))).isPresent();
    }

    @Test
    void routesAlias() throws Exception {
        assertThat(dispatcher.dispatch(ctx("/ping"))).isPresent();
    }

    @Test
    void unknownCommandYieldsEmpty() throws Exception {
        assertThat(dispatcher.dispatch(ctx("/unknown"))).isEmpty();
    }

    @Test
    void contextWithoutCommandYieldsEmpty() throws Exception {
        assertThat(dispatcher.dispatch(ctx(null))).isEmpty();
    }

    @Test
    void nullContextYieldsEmpty() throws Exception {
        assertThat(dispatcher.dispatch(null)).isEmpty();
    }

    @Test
    void handlerFailureIsWrappedAsProjectException() {
        CommandDispatcher failing =
                new CommandDispatcher(new CommandRegistry(List.of(new BoomHandler())));

        assertThatThrownBy(() -> failing.dispatch(ctx("/boom")))
                .isInstanceOf(TggDispatchException.class)
                .hasMessageContaining("命令处理失败");
    }

    @BotCommand("boom")
    static class BoomHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            throw new IllegalStateException("模拟失败");
        }
    }

    private static UpdateContext ctx(String command) {
        return new UpdateContext(1, 42L, -100L, command);
    }
}

package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.exception.TggDispatchException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.permission.Permission;
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
    void unknownCommandRepliesFriendlyHint() throws Exception {
        Optional<BotApiMethod<?>> reply = dispatcher.dispatch(ctx("/unknown"));

        assertThat(reply).isPresent();
        String text = ((SendMessage) reply.orElseThrow()).getText();
        assertThat(text).as("未注册命令不再静默——给固定友好提示").isEqualTo(DispatchMessages.UNKNOWN_COMMAND_REPLY);
        assertThat(text).as("提示里要给出可照抄的出路").contains("/menu");
    }

    /**
     * 回归（不变量）：已注册但**权限不足**的命令仍静默——回复"权限不足"等于向无权者确认命令存在
     * （见 {@code CommandDispatcher} 类的静默取舍）。未注册命令的提示不得波及这条路径。
     */
    @Test
    void knownButUnauthorizedCommandStaysSilent() throws Exception {
        CommandDispatcher restricted = new CommandDispatcher(new CommandRegistry(
                List.of(new EchoCommandHandler(), new AdminOnlyHandler())));

        assertThat(restricted.dispatch(ctx("/admin_only")))
                .as("已注册但权限不足：静默，不暴露命令存在").isEmpty();
    }

    /**
     * 回归（安全）：{@code /echo} 这类 publicCommand 的文本**仍要送审**，不得因「未注册命令提示」
     * 的改动而顺带豁免——它的参数会原样留在群里，豁免等于给"用公开命令停放违规正文"开口子。
     *
     * <p>钉住 {@code willExecute} 与 {@code exemptFromModeration} 两条判定语义不被本次改动影响。
     */
    @Test
    void publicCommandTextIsStillSentToModeration() throws Exception {
        UpdateContext echo = ctx("/echo");

        assertThat(dispatcher.willExecute(echo)).as("公开命令会真的执行").isTrue();
        assertThat(dispatcher.exemptFromModeration(echo)).as("但不得豁免审核").isFalse();
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

    /** 需要管理权限的命令——用于验证「已注册但权限不足仍静默」。 */
    @BotCommand(value = "admin_only", requiredPermission = Permission.MANAGE_CONFIG)
    static class AdminOnlyHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return null;
        }
    }

    private static UpdateContext ctx(String command) {
        return new UpdateContext(1, 42L, -100L, command);
    }
}

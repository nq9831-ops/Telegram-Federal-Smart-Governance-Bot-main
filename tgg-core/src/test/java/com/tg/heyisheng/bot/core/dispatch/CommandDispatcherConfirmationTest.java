package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.interaction.ConfirmationGranted;
import com.tg.heyisheng.bot.core.interaction.ConfirmationStore;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确认卡在**分发器**这一层的拦截测试（不是 handler 级）。
 *
 * <p><b>为什么要测这一层</b>：拦截点选在这里，是为了让「危险命令不必各自记得先确认」，
 * 一次标注对所有入口生效。因此真正需要被钉住的是「分发器会不会替它拦」——
 * handler 自己的行为不是本测试的关注点。
 */
class CommandDispatcherConfirmationTest {

    private static final long CHAT = -100L;
    private static final long USER = 42L;

    private static final AtomicInteger CALLS = new AtomicInteger();

    @BotCommand(value = "danger", description = "危险操作", requiredPermission = Permission.MANAGE_CONFIG,
            confirm = Confirm.ALWAYS)
    static class DangerHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            CALLS.incrementAndGet();
            return new SendMessage(String.valueOf(ctx.chatId()), "已执行");
        }
    }

    /** 无参时只读、带参时写——用于验证 {@code WHEN_ARGS}。 */
    @BotCommand(value = "mixed", description = "按参数决定", requiredPermission = Permission.MANAGE_CONFIG,
            confirm = Confirm.WHEN_ARGS)
    static class MixedHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            CALLS.incrementAndGet();
            return new SendMessage(String.valueOf(ctx.chatId()), "已执行");
        }
    }

    @BotCommand(value = "safe", description = "普通命令", requiredPermission = Permission.MANAGE_CONFIG)
    static class SafeHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            CALLS.incrementAndGet();
            return new SendMessage(String.valueOf(ctx.chatId()), "ok");
        }
    }

    @BeforeEach
    void resetCalls() {
        CALLS.set(0);
    }

    private static CommandDispatcher dispatcher(ConfirmationRequests seam) {
        CommandRegistry registry = new CommandRegistry(
                List.of(new DangerHandler(), new MixedHandler(), new SafeHandler()));
        return new CommandDispatcher(registry, new PermissionChecker((c, u) -> Role.ADMIN), seam);
    }

    private static ConfirmationStore store() {
        return new ConfirmationStore(Clock.systemUTC());
    }

    private static UpdateContext ctx(String command, String args) {
        return new UpdateContext(1, USER, CHAT, 9, command, args);
    }

    /** 核心：危险命令先给卡、**不执行**，并登记了一次待确认操作。 */
    @Test
    void dangerousCommandYieldsCardWithoutExecuting() throws Exception {
        ConfirmationStore seam = store();

        Optional<BotApiMethod<?>> result = dispatcher(seam).dispatch(ctx("danger", "广告"));

        assertThat(result).isPresent();
        SendMessage card = (SendMessage) result.orElseThrow();
        assertThat(card.getReplyMarkup()).as("应给出确认键盘").isInstanceOf(InlineKeyboardMarkup.class);
        assertThat(card.getText()).as("必须复述将要执行的命令").contains("/danger 广告");
        assertThat(CALLS.get()).as("确认之前绝不能执行").isZero();
        assertThat(seam.size()).as("必须登记了待确认操作").isEqualTo(1);
    }

    /** 带上确认标记后（只可能来自令牌消费）才真的执行。 */
    @Test
    void grantedExecutionActuallyRuns() throws Exception {
        UpdateContext confirmed = ctx("danger", "广告");
        confirmed.attach(new ConfirmationGranted());

        Optional<BotApiMethod<?>> result = dispatcher(store()).dispatch(confirmed);

        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(((SendMessage) result.orElseThrow()).getText()).isEqualTo("已执行");
    }

    @Test
    void whenArgsInterruptsOnlyWhenArgumentsPresent() throws Exception {
        ConfirmationStore seam = store();

        dispatcher(seam).dispatch(ctx("mixed", null));
        assertThat(CALLS.get()).as("无参=只读，不该打断").isEqualTo(1);

        dispatcher(seam).dispatch(ctx("mixed", "写点东西"));
        assertThat(CALLS.get()).as("带参=写操作，应拦下").isEqualTo(1);
        assertThat(seam.size()).isEqualTo(1);
    }

    @Test
    void safeCommandIsNotInterrupted() throws Exception {
        ConfirmationStore seam = store();

        dispatcher(seam).dispatch(ctx("safe", null));

        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(seam.size()).as("普通命令不该产生待确认项").isZero();
    }

    /** 未装配接缝（老构造器）→ 不拦截：既有测试与未启用交互模块的装配行为不变。 */
    @Test
    void withoutSeamNothingIsInterrupted() throws Exception {
        dispatcher(null).dispatch(ctx("danger", null));

        assertThat(CALLS.get()).isEqualTo(1);
    }
}

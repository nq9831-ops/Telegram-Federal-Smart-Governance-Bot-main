package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.permission.InMemoryRoleSource;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.Role;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 命令级权限门控测试：{@code @BotCommand(requiredPermission = ...)} 是否真的拦得住。
 */
class CommandPermissionGateTest {

    private static final long CHAT = -100L;
    private static final long USER = 42L;

    @BotCommand(value = "ban", requiredPermission = Permission.BAN_USER)
    static class BanStub implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "banned");
        }
    }

    private final InMemoryRoleSource roleSource = new InMemoryRoleSource();
    private final CommandDispatcher dispatcher = new CommandDispatcher(
            new CommandRegistry(List.of(new BanStub())),
            new PermissionChecker(roleSource));

    @Test
    void rejectsWhenUserLacksTheRequiredPermission() throws Exception {
        assertThat(dispatcher.dispatch(ctx("/ban")))
                .as("普通成员不得执行 /ban").isEmpty();
    }

    @Test
    void allowsWhenUserHasTheRequiredPermission() throws Exception {
        roleSource.assign(CHAT, USER, Role.MODERATOR);

        assertThat(dispatcher.dispatch(ctx("/ban"))).isPresent();
    }

    /** 未声明权限要求的命令对所有人开放——既有命令的行为不得被本次改动改变。 */
    @Test
    void commandWithoutPermissionRequirementStaysOpenToAll() throws Exception {
        CommandDispatcher openDispatcher = new CommandDispatcher(
                new CommandRegistry(List.of(new EchoCommandHandler())),
                new PermissionChecker(roleSource));

        assertThat(openDispatcher.dispatch(ctx("/echo"))).isPresent();
    }

    @Test
    void aliasInheritsThePermissionRequirement() throws Exception {
        CommandDispatcher aliasDispatcher = new CommandDispatcher(
                new CommandRegistry(List.of(new EchoStubWithAlias())),
                new PermissionChecker(roleSource));

        assertThat(aliasDispatcher.dispatch(ctx("/ping")))
                .as("别名必须继承主命令的权限要求，否则可绕过").isEmpty();
    }

    @BotCommand(value = "echo2", aliases = {"ping"}, requiredPermission = Permission.MANAGE_CONFIG)
    static class EchoStubWithAlias implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return null;
        }
    }

    private static UpdateContext ctx(String command) {
        return new UpdateContext(1, USER, CHAT, command);
    }
}

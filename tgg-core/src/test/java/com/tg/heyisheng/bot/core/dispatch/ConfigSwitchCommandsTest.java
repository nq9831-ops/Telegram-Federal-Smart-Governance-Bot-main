package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigView;
import com.tg.heyisheng.bot.core.permission.InMemoryRoleSource;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.Role;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 功能开关的读写入口测试。
 *
 * <p>本测试的存在意义：在此之前 {@code setEnabled} 无任何生产调用方，
 * 开关在生产中不可达（审查标记为 HIGH）。这里断言它<b>既有入口、又受权限约束</b>。
 */
class ConfigSwitchCommandsTest {

    private static final long CHAT = -100L;
    private static final long ADMIN = 42L;
    private static final long MEMBER = 999L;

    private final GroupConfigService service = mock(GroupConfigService.class);
    private final InMemoryRoleSource roleSource = new InMemoryRoleSource();
    private final CommandRegistry registry =
            new CommandRegistry(List.of(new DisableCommandHandler(service), new EnableCommandHandler(service)));
    private final CommandDispatcher dispatcher =
            new CommandDispatcher(registry, new PermissionChecker(roleSource));

    @Test
    void disableWritesTheSwitch() throws Exception {
        roleSource.assign(CHAT, ADMIN, Role.ADMIN);

        assertThat(dispatcher.dispatch(ctx(ADMIN, "/disable"))).isPresent();

        verify(service).setEnabled(CHAT, false);
    }

    @Test
    void enableWritesTheSwitch() throws Exception {
        roleSource.assign(CHAT, ADMIN, Role.ADMIN);

        assertThat(dispatcher.dispatch(ctx(ADMIN, "/enable"))).isPresent();

        verify(service).setEnabled(CHAT, true);
    }

    /** 开关是管理动作，普通成员不得触碰——否则任何人都能关掉整群功能。 */
    @Test
    void ordinaryMemberCannotToggleTheSwitch() throws Exception {
        assertThat(dispatcher.dispatch(ctx(MEMBER, "/disable")))
                .as("无 MANAGE_CONFIG 权限者应被拦下").isEmpty();
        assertThat(dispatcher.dispatch(ctx(MEMBER, "/enable"))).isEmpty();

        verify(service, never()).setEnabled(CHAT, false);
        verify(service, never()).setEnabled(CHAT, true);
    }

    @Test
    void commandsDeclareManageConfigPermission() {
        assertThat(registry.requiredPermission("/disable")).isEqualTo(Permission.MANAGE_CONFIG);
        assertThat(registry.requiredPermission("/enable")).isEqualTo(Permission.MANAGE_CONFIG);
    }

    /**
     * 回归（关键）：群被关闭后，{@code /enable} 仍必须可用。
     *
     * <p>背景：早期实现把开关判断放在中间件里直接中断链，导致 {@code /disable} 之后
     * 连 {@code /enable} 都进不来，该群<b>永久锁死</b>（端到端验收时实测暴露，
     * 表现为 /enable 无任何响应且库中 enabled 仍为 0）。
     * 本用例是该缺陷的护栏：若有人再把开关判断挪回中间件，它会立刻变红。
     */
    @Test
    void enableStillWorksWhenGroupIsDisabled() throws Exception {
        roleSource.assign(CHAT, ADMIN, Role.ADMIN);
        UpdateContext disabledCtx = ctx(ADMIN, "/enable");
        disabledCtx.attach(new GroupConfigView(CHAT, "已关闭的群", false));

        assertThat(dispatcher.dispatch(disabledCtx))
                .as("恢复类命令在关闭状态下必须放行，否则群会被永久锁死")
                .isPresent();

        verify(service).setEnabled(CHAT, true);
    }

    /** 反向断言：非恢复类命令在关闭状态下必须被拒（同一机制不能放行一切）。 */
    @Test
    void ordinaryCommandIsRejectedWhenGroupIsDisabled() throws Exception {
        UpdateContext disabledCtx = ctx(MEMBER, "/echo");
        disabledCtx.attach(new GroupConfigView(CHAT, "已关闭的群", false));

        CommandDispatcher echoDispatcher = new CommandDispatcher(
                new CommandRegistry(List.of(new EchoCommandHandler())),
                new PermissionChecker(roleSource));

        assertThat(echoDispatcher.dispatch(disabledCtx))
                .as("关闭状态下普通命令不得执行")
                .isEmpty();
    }

    /** 未挂载配置（不装配中间件的场景，如纯单元测试）时按启用处理，行为不变。 */
    @Test
    void commandRunsWhenNoConfigAttached() throws Exception {
        CommandDispatcher echoDispatcher = new CommandDispatcher(
                new CommandRegistry(List.of(new EchoCommandHandler())),
                new PermissionChecker(roleSource));

        assertThat(echoDispatcher.dispatch(ctx(MEMBER, "/echo"))).isPresent();
    }

    @Test
    void repliesTellTheUserWhatHappened() throws Exception {
        roleSource.assign(CHAT, ADMIN, Role.ADMIN);

        var reply = dispatcher.dispatch(ctx(ADMIN, "/disable"));

        assertThat(reply).isPresent();
        assertThat(((SendMessage) reply.get()).getText())
                .as("用户可见文案必须非空且明确")
                .isEqualTo(DisableCommandHandler.REPLY);
    }

    private static UpdateContext ctx(long userId, String command) {
        return new UpdateContext(1, userId, CHAT, command);
    }
}

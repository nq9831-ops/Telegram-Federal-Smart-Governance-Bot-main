package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.Role;
import com.tg.heyisheng.bot.core.permission.RoleGrant;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChatMember;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 命令菜单注册：客户端 {@code /} 菜单已收敛为**单一入口** {@code /menu}。
 *
 * <p>四条不变量：① 客户端菜单只含显式声明 {@code clientMenu} 的命令（当前即 {@code /menu}）；
 * ② 自助命令（{@code publicCommand}）**不进**客户端菜单——它由 {@code /menu} 面板承载；
 * ③ 接缝类（平台白名单）不进任何档（Telegram 没有「全局按人」的 scope）；
 * ④ 每条授权都生成一个与默认档**同内容**的逐成员档——只为覆盖旧版按权限分档遗留的 per-user 菜单。
 *
 * <p>{@code setMyCommands} 是**按 scope 全量替换**——一个非法条目会让该档整批失败、菜单保持原样，
 * 所以本地必须先过滤再发送。
 *
 * <p>⚠️ 本类**刻意不 import** TelegramBots 的 {@code ...objects.commands.BotCommand}：
 * 它与本包的命令注解 {@code @BotCommand} **同名不同物**，一旦 import 就会把注解名遮蔽掉。
 * 需要引用该数据对象时用 {@code var} 或 {@link #names} 间接处理。
 */
class CommandMenuRegistrarTest {

    private static final long CHAT = -100900999L;
    private static final long USER = 42L;

    // ────────────────────────── 测试用命令 ──────────────────────────

    /** 客户端菜单入口：声明 clientMenu 才进客户端菜单（当前唯一即 /menu）。 */
    @BotCommand(value = "menu", description = "显示你可用的功能", clientMenu = true)
    static class MenuHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "menu");
        }
    }

    /** 自助命令：声明 publicCommand → 进 /menu 面板，但**不进**客户端菜单。 */
    @BotCommand(value = "echo", description = "连通性测试", publicCommand = true)
    static class EchoHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "pong");
        }
    }

    /**
     * 无权限点、未被接缝认领、又**没声明** {@code publicCommand}/{@code clientMenu}——三不管，
     * 按 fail-closed 不进任何档。
     */
    @BotCommand(value = "mystery", description = "谁都没管的命令")
    static class MysteryHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "?");
        }
    }

    @BotCommand(value = "words", description = "查看本群违禁词", requiredPermission = Permission.MANAGE_CONFIG)
    static class WordsHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "词表");
        }
    }

    /** 平台白名单类：注解无权限点，可见性由接缝给出——**不进客户端菜单**。 */
    @BotCommand(value = "review_list", description = "待复核队列")
    static class ReviewListHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "队列");
        }
    }

    /** 带权限点却声明 clientMenu——配置矛盾，必须 fail-closed 剔除（否则向无权者暴露其存在）。 */
    @BotCommand(value = "danger", description = "带权限点的入口", requiredPermission = Permission.BAN_USER,
            clientMenu = true)
    static class DangerHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "danger");
        }
    }

    private static final Set<String> SEAMED = Set.of("review_list");

    private static CommandRegistry registry() {
        return new CommandRegistry(List.of(
                new MenuHandler(), new EchoHandler(), new MysteryHandler(),
                new WordsHandler(), new ReviewListHandler(), new DangerHandler()));
    }

    /** 命令名列表——避开 TelegramBots {@code BotCommand} 的类型名（见类 javadoc 的警告）。 */
    private static List<String> names(
            List<org.telegram.telegrambots.meta.api.objects.commands.BotCommand> commands) {
        return commands.stream()
                .map(org.telegram.telegrambots.meta.api.objects.commands.BotCommand::getCommand)
                .toList();
    }

    /** 记录被发送的方法并**如实回报成功**，替代真实网络调用。 */
    private static final class CapturingSender implements CommandMenuRegistrar.Sender {
        private final List<BotApiMethod<?>> sent = new ArrayList<>();

        @Override
        public boolean send(BotApiMethod<?> method) {
            sent.add(method);
            return true;
        }
    }

    private static Map<String, String> menu(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    // ────────────────────────── 组装（纯逻辑） ──────────────────────────

    @Test
    void convertsMapToTelegramCommands() {
        var commands = CommandMenuRegistrar.toMenuCommands(
                menu("echo", "连通性测试", "words", "查看本群违禁词"));

        assertThat(names(commands)).containsExactly("echo", "words");
        assertThat(commands.get(0).getDescription()).isEqualTo("连通性测试");
    }

    /** 空描述在客户端会渲染成一行空白——跳过，而不是塞一个看不懂的空壳。 */
    @Test
    void skipsBlankDescription() {
        var commands = CommandMenuRegistrar.toMenuCommands(menu("echo", "连通性测试", "no_desc", "  "));

        assertThat(names(commands)).containsExactly("echo");
    }

    /** Telegram 命令名只接受 {@code [a-z0-9_]{1,32}}；非法名会让整批注册失败，必须在本地剔除。 */
    @Test
    void skipsNamesTelegramWouldReject() {
        var commands = CommandMenuRegistrar.toMenuCommands(menu(
                "echo", "ok",
                "Bad-Name", "含连字符",
                "way_too_long_command_name_超过三十二个字符的示例", "太长",
                "中文名", "非法"));

        assertThat(names(commands)).containsExactly("echo");
    }

    /** 描述上限 256，超出即截断（超长同样会让整批注册失败）。 */
    @Test
    void truncatesOverlongDescription() {
        var commands = CommandMenuRegistrar.toMenuCommands(menu("echo", "长".repeat(400)));

        assertThat(commands).hasSize(1);
        assertThat(commands.get(0).getDescription()).hasSize(CommandMenuRegistrar.MAX_DESCRIPTION);
    }

    /**
     * 客户端菜单的描述也要去掉权限括注——与 {@code /menu} 的按钮文案**共用同一份清洗**。
     *
     * <p>两个入口都已按权限过滤：能看见这条命令的人必然有权限，括注是噪声；
     * 更糟的是两处各写一份清洗时文案会不一致，同一条命令看起来像两条。
     */
    @Test
    void dropsTheTrailingPermissionParenthetical() {
        var commands = CommandMenuRegistrar.toMenuCommands(
                menu("words", "查看本群违禁词（需管理员权限）"));

        assertThat(commands.get(0).getDescription()).isEqualTo("查看本群违禁词");
    }

    /** 描述只剩权限括注时会被清空——按既有契约「空描述不进菜单」，不能变成一行空白。 */
    @Test
    void dropsCommandsWhoseDescriptionIsOnlyAPermissionNote() {
        var commands = CommandMenuRegistrar.toMenuCommands(
                menu("echo", "连通性测试", "words", "（需管理员权限）"));

        assertThat(names(commands)).containsExactly("echo");
    }

    // ────────────────────────── 分档 ──────────────────────────

    /** 客户端菜单只含显式声明 {@code clientMenu} 的命令——当前即 /menu。 */
    @Test
    void clientMenuHoldsOnlyExplicitlyDeclaredEntries() {
        List<CommandMenuRegistrar.ScopedMenu> menus =
                CommandMenuRegistrar.planMenus(registry(), SEAMED, List.of());

        assertThat(menus).hasSize(1);
        assertThat(menus.get(0).label()).isEqualTo("default");
        assertThat(menus.get(0).scope()).isInstanceOf(BotCommandScopeDefault.class);
        assertThat(names(menus.get(0).commands())).containsExactly("menu");
    }

    /** 自助命令（publicCommand）**不进**客户端菜单——它由 /menu 面板承载。 */
    @Test
    void publicCommandDoesNotEnterClientMenu() {
        List<CommandMenuRegistrar.ScopedMenu> menus =
                CommandMenuRegistrar.planMenus(registry(), SEAMED, List.of());

        assertThat(names(menus.get(0).commands())).doesNotContain("echo");
    }

    /** 带权限点的命令即使声明 clientMenu 也不进（fail-closed）——否则向无权者暴露其存在。 */
    @Test
    void clientMenuCommandWithPermissionIsRejected() {
        List<CommandMenuRegistrar.ScopedMenu> menus =
                CommandMenuRegistrar.planMenus(registry(), SEAMED, List.of());

        assertThat(names(menus.get(0).commands())).doesNotContain("danger");
    }

    /**
     * fail-closed：无权限点、未被接缝认领、又没声明 {@code publicCommand}/{@code clientMenu}
     * 的命令**不进任何档**——它既不对所有人可见，也不该经面板暴露。
     */
    @Test
    void unreachableCommandNeverAppearsInAnyTier() {
        List<CommandMenuRegistrar.ScopedMenu> menus = CommandMenuRegistrar.planMenus(
                registry(), SEAMED, List.of(new RoleGrant(CHAT, USER, Role.ADMIN)));

        assertThat(menus).allSatisfy(menu -> assertThat(names(menu.commands()))
                .as("档 %s 不得含三不管命令", menu.label())
                .doesNotContain("mystery"));
    }

    /** 接缝类（平台白名单）不进任何档——包括逐成员档。 */
    @Test
    void seamCommandsNeverAppearInAnyTier() {
        List<CommandMenuRegistrar.ScopedMenu> menus = CommandMenuRegistrar.planMenus(
                registry(), SEAMED, List.of(new RoleGrant(CHAT, USER, Role.ADMIN)));

        assertThat(menus).allSatisfy(m -> assertThat(names(m.commands()))
                .as("档 %s 不得含接缝类命令", m.label())
                .doesNotContain("review_list"));
    }

    /**
     * 每条授权都生成一个与默认档**同内容**的逐成员档——只为覆盖旧版遗留的 per-user 菜单
     * （客户端菜单已全局统一，逐成员档不再按角色过滤）。
     */
    @Test
    void everyGrantGetsAMemberTierWithTheSameContent() {
        List<CommandMenuRegistrar.ScopedMenu> menus = CommandMenuRegistrar.planMenus(
                registry(), SEAMED, List.of(new RoleGrant(CHAT, USER, Role.MODERATOR)));

        assertThat(menus).hasSize(2);
        CommandMenuRegistrar.ScopedMenu member = menus.get(1);
        assertThat(member.label()).isEqualTo("member:" + CHAT + ":" + USER);
        assertThat(member.scope()).isInstanceOf(BotCommandScopeChatMember.class);
        assertThat(names(member.commands()))
                .as("逐成员档内容与默认档一致")
                .containsExactlyElementsOf(names(menus.get(0).commands()));
    }

    @Test
    void multipleGrantsProduceOneTierEach() {
        List<CommandMenuRegistrar.ScopedMenu> menus = CommandMenuRegistrar.planMenus(registry(), SEAMED,
                List.of(new RoleGrant(CHAT, USER, Role.ADMIN),
                        new RoleGrant(CHAT, 7L, Role.OWNER)));

        assertThat(menus).extracting(CommandMenuRegistrar.ScopedMenu::label)
                .containsExactly("default", "member:" + CHAT + ":" + USER, "member:" + CHAT + ":7");
    }

    // ────────────────────────── 发送 ──────────────────────────

    @Test
    void sendsOneSetMyCommandsPerTierWithItsScope() {
        CapturingSender sender = new CapturingSender();
        CommandMenuRegistrar registrar = new CommandMenuRegistrar(
                CommandMenuRegistrar.planMenus(registry(), SEAMED,
                        List.of(new RoleGrant(CHAT, USER, Role.ADMIN))),
                sender);

        List<String> registered = registrar.register();

        assertThat(registered).containsExactly("default", "member:" + CHAT + ":" + USER);
        assertThat(sender.sent).hasSize(2).allSatisfy(m -> assertThat(m).isInstanceOf(SetMyCommands.class));
        SetMyCommands first = (SetMyCommands) sender.sent.get(0);
        SetMyCommands second = (SetMyCommands) sender.sent.get(1);
        assertThat(first.getScope()).isInstanceOf(BotCommandScopeDefault.class);
        assertThat(second.getScope()).isInstanceOf(BotCommandScopeChatMember.class);
        assertThat(names(first.getCommands())).containsExactly("menu");
        assertThat(names(second.getCommands())).as("逐成员档与默认档同内容").containsExactly("menu");
    }

    /**
     * 逐档隔离：某档发送失败不得影响其余档，**也不得被谎报为已注册**。
     *
     * <p>这里刻意用**真实的失败模式**——返回 {@code false} 而非抛异常：生产的 sender 是
     * {@code TelegramApiMethodExecutor#execute}，它捕获所有异常后 {@code return false}，
     * 从不抛出。若测试只会模拟「抛异常」，就会给「逐档隔离已生效」一个虚假的绿灯。
     */
    @Test
    void failingTierIsIsolatedAndNotReportedAsRegistered() {
        List<BotApiMethod<?>> sent = new ArrayList<>();
        CommandMenuRegistrar.Sender flaky = method -> {
            if (((SetMyCommands) method).getScope() instanceof BotCommandScopeChatMember) {
                return false;
            }
            sent.add(method);
            return true;
        };
        CommandMenuRegistrar registrar = new CommandMenuRegistrar(
                CommandMenuRegistrar.planMenus(registry(), SEAMED,
                        List.of(new RoleGrant(CHAT, USER, Role.ADMIN))),
                flaky);

        List<String> registered = registrar.register();

        assertThat(registered).as("失败的档既不影响其余档，也不进「已注册」").containsExactly("default");
        assertThat(sent).hasSize(1);
    }

    /** 兜底：sender 实现若选择**抛出**（而非返回 false），同样逐档隔离。 */
    @Test
    void throwingSenderIsAlsoIsolated() {
        List<BotApiMethod<?>> sent = new ArrayList<>();
        CommandMenuRegistrar.Sender throwing = method -> {
            if (((SetMyCommands) method).getScope() instanceof BotCommandScopeChatMember) {
                throw new IllegalStateException("boom");
            }
            sent.add(method);
            return true;
        };
        CommandMenuRegistrar registrar = new CommandMenuRegistrar(
                CommandMenuRegistrar.planMenus(registry(), SEAMED,
                        List.of(new RoleGrant(CHAT, USER, Role.ADMIN))),
                throwing);

        assertThat(registrar.register()).containsExactly("default");
        assertThat(sent).hasSize(1);
    }

    /** 无 bot token 时**不发送**（也不静默）：返回空列表，由装配层/日志交代原因。 */
    @Test
    void doesNothingWithoutSender() {
        CommandMenuRegistrar registrar = new CommandMenuRegistrar(
                CommandMenuRegistrar.planMenus(registry(), SEAMED, List.of()), null);

        assertThat(registrar.register()).isEmpty();
    }

    /** 某档命令集为空时不应发出一个空菜单（空列表是合法的，会清掉该 scope 的既有菜单——非本意）。 */
    @Test
    void doesNotSendEmptyTier() {
        CapturingSender sender = new CapturingSender();
        // 把唯一一条命令也标成接缝类 → 默认档为空
        CommandMenuRegistrar registrar = new CommandMenuRegistrar(
                CommandMenuRegistrar.planMenus(new CommandRegistry(List.of(new ReviewListHandler())),
                        Set.of("review_list"), List.of()),
                sender);

        assertThat(registrar.register()).isEmpty();
        assertThat(sender.sent).isEmpty();
    }
}

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
 * 命令菜单**按权限分档**注册（客户端输入 {@code /} 时的提示菜单）。
 *
 * <p>三条不变量：① 默认档只含公开命令——普通成员不该看到任何管理命令名；
 * ② 接缝类（平台白名单）**不进任何档**（Telegram 没有「全局按人」的 scope）；
 * ③ 逐成员档按该条授权的**角色**过滤（{@code MODERATOR} 不该看到它跑不了的命令）。
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

    @BotCommand(value = "echo", description = "连通性测试", publicCommand = true)
    static class EchoHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "pong");
        }
    }

    /**
     * 无权限点、未被接缝认领、又**没声明** {@code publicCommand}——按 fail-closed 不进任何档。
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

    @BotCommand(value = "teach", description = "教一条规则", requiredPermission = Permission.TEACH_RULE)
    static class TeachHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "已教");
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

    private static final Set<String> SEAMED = Set.of("review_list");

    private static CommandRegistry registry() {
        return new CommandRegistry(List.of(
                new EchoHandler(), new MysteryHandler(), new WordsHandler(), new TeachHandler(),
                new ReviewListHandler()));
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

    // ────────────────────────── 分档 ──────────────────────────

    /** 默认档只含**公开**命令——普通成员不该在客户端看到管理命令名。 */
    @Test
    void defaultTierHoldsOnlyPublicCommands() {
        List<CommandMenuRegistrar.ScopedMenu> menus =
                CommandMenuRegistrar.planMenus(registry(), SEAMED, List.of());

        assertThat(menus).hasSize(1);
        assertThat(menus.get(0).label()).isEqualTo("default");
        assertThat(menus.get(0).scope()).isInstanceOf(BotCommandScopeDefault.class);
        assertThat(names(menus.get(0).commands())).containsExactly("echo");
    }

    /**
     * fail-closed：无权限点、未被接缝认领、又没声明 {@code publicCommand} 的命令**不进任何档**。
     *
     * <p>反过来的默认（公开）会有一个静默且危险的失效方式：有人新增一条「门控写在 handler 内、
     * 注解权限留 {@code NONE}」的平台命令，只要忘了登记可见性接缝，它就会被默认档广播给所有人
     * ——正是「客户端菜单向无权者暴露命令」那个原始缺陷的原样回归。
     */
    @Test
    void unlistedCommandNeverAppearsInAnyTier() {
        List<CommandMenuRegistrar.ScopedMenu> menus = CommandMenuRegistrar.planMenus(
                registry(), SEAMED, List.of(new RoleGrant(CHAT, USER, Role.ADMIN)));

        assertThat(menus).allSatisfy(menu -> assertThat(names(menu.commands()))
                .as("档 %s 不得含未声明 publicCommand 的命令", menu.label())
                .doesNotContain("mystery"));
    }

    /** 接缝类（平台白名单）不进任何档——包括管理档。 */
    @Test
    void seamCommandsNeverAppearInAnyTier() {
        List<CommandMenuRegistrar.ScopedMenu> menus = CommandMenuRegistrar.planMenus(
                registry(), SEAMED, List.of(new RoleGrant(CHAT, USER, Role.ADMIN)));

        assertThat(menus).allSatisfy(m -> assertThat(names(m.commands()))
                .as("档 %s 不得含接缝类命令", m.label())
                .doesNotContain("review_list"));
    }

    /** 有管理权的授权 → 多一档「公开 + 管理」，且 scope 精确到 (chatId, userId)。 */
    @Test
    void adminGrantGetsPublicPlusManagementTier() {
        List<CommandMenuRegistrar.ScopedMenu> menus = CommandMenuRegistrar.planMenus(
                registry(), SEAMED, List.of(new RoleGrant(CHAT, USER, Role.ADMIN)));

        assertThat(menus).hasSize(2);
        CommandMenuRegistrar.ScopedMenu member = menus.get(1);
        assertThat(member.label()).isEqualTo("member:" + CHAT + ":" + USER);
        assertThat(member.scope()).isInstanceOf(BotCommandScopeChatMember.class);
        assertThat(names(member.commands())).containsExactly("echo", "teach", "words");
    }

    /**
     * 按**该条授权的角色**过滤：{@code MODERATOR} 只有 {@code BAN_USER}，跑不了
     * {@code MANAGE_CONFIG} / {@code TEACH_RULE} 的命令——不该为他多生成一档，
     * 否则又是「显示了却用不了」。
     */
    @Test
    void moderatorGrantGetsNoExtraTier() {
        List<CommandMenuRegistrar.ScopedMenu> menus = CommandMenuRegistrar.planMenus(
                registry(), SEAMED, List.of(new RoleGrant(CHAT, USER, Role.MODERATOR)));

        assertThat(menus).hasSize(1);
        assertThat(menus.get(0).label()).isEqualTo("default");
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
        assertThat(names(first.getCommands())).containsExactly("echo");
        assertThat(names(second.getCommands())).containsExactly("echo", "teach", "words");
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

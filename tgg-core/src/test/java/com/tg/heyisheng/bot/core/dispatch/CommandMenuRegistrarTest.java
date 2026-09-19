package com.tg.heyisheng.bot.core.dispatch;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动期把命令清单注册到 Telegram（客户端输入 {@code /} 时的提示菜单）。
 *
 * <p>真实环境实测（2026-09-19 · 公网 bot）：{@code getMyCommands} 返回 **0 条**，
 * 于是 29 个命令在客户端里毫无提示。本类把它补上，并把这些边界钉住：
 * Telegram 的 {@code setMyCommands} 是**全量替换**——一个非法条目会让整个请求失败、菜单保持空，
 * 所以本地必须先过滤再发送。
 */
class CommandMenuRegistrarTest {

    /** 记录被发送的方法，替代真实网络调用。 */
    private static final class CapturingSender implements Consumer<BotApiMethod<?>> {
        private final List<BotApiMethod<?>> sent = new ArrayList<>();

        @Override
        public void accept(BotApiMethod<?> method) {
            sent.add(method);
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
        List<BotCommand> commands = CommandMenuRegistrar.toMenuCommands(
                menu("echo", "连通性测试", "words", "查看本群违禁词"));

        assertThat(commands).extracting(BotCommand::getCommand).containsExactly("echo", "words");
        assertThat(commands.get(0).getDescription()).isEqualTo("连通性测试");
    }

    /** 空描述在客户端会渲染成一行空白——跳过，而不是塞一个看不懂的空壳。 */
    @Test
    void skipsBlankDescription() {
        List<BotCommand> commands = CommandMenuRegistrar.toMenuCommands(
                menu("echo", "连通性测试", "no_desc", "  "));

        assertThat(commands).extracting(BotCommand::getCommand).containsExactly("echo");
    }

    /** Telegram 命令名只接受 {@code [a-z0-9_]{1,32}}；非法名会让整批注册失败，必须在本地剔除。 */
    @Test
    void skipsNamesTelegramWouldReject() {
        List<BotCommand> commands = CommandMenuRegistrar.toMenuCommands(
                menu("echo", "ok", "Bad-Name", "含连字符", "way_too_long_command_name_超过三十二个字符的示例", "太长", "中文名", "非法"));

        assertThat(commands).extracting(BotCommand::getCommand).containsExactly("echo");
    }

    /** 描述上限 256，超出即截断（超长同样会让整批注册失败）。 */
    @Test
    void truncatesOverlongDescription() {
        String long_ = "长".repeat(400);

        List<BotCommand> commands = CommandMenuRegistrar.toMenuCommands(menu("echo", long_));

        assertThat(commands).hasSize(1);
        assertThat(commands.get(0).getDescription()).hasSize(CommandMenuRegistrar.MAX_DESCRIPTION);
    }

    // ────────────────────────── 发送 ──────────────────────────

    @Test
    void sendsOneSetMyCommandsWithAllValidEntries() {
        CapturingSender sender = new CapturingSender();
        CommandMenuRegistrar registrar = new CommandMenuRegistrar(
                menu("echo", "连通性测试", "words", "查看本群违禁词"), sender);

        List<String> registered = registrar.register();

        assertThat(registered).containsExactly("echo", "words");
        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0)).isInstanceOf(SetMyCommands.class);
        assertThat(((SetMyCommands) sender.sent.get(0)).getCommands())
                .extracting(BotCommand::getCommand).containsExactly("echo", "words");
    }

    /** 无 bot token 时**不发送**（也不静默）：返回空列表，由装配层/日志交代原因。 */
    @Test
    void doesNothingWithoutSender() {
        CommandMenuRegistrar registrar = new CommandMenuRegistrar(menu("echo", "连通性测试"), null);

        assertThat(registrar.register()).isEmpty();
    }

    /** 全部命令都没有描述时不应发出一个空菜单（空列表是合法的，会清掉既有菜单——非本意）。 */
    @Test
    void doesNotSendEmptyMenu() {
        CapturingSender sender = new CapturingSender();
        CommandMenuRegistrar registrar = new CommandMenuRegistrar(menu("a", "", "b", " "), sender);

        assertThat(registrar.register()).isEmpty();
        assertThat(sender.sent).isEmpty();
    }
}

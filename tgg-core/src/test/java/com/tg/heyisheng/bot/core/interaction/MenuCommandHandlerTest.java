package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigView;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /menu} 入口面板测试。
 *
 * <p><b>三个要点</b>：① 只列「本群此人真的能用」的管理命令；② 谁都无权时不列空卡、回一句说明；
 * ③ 停用群里 {@code /enable} 必须仍在（否则该群永久锁死——项目实测过的缺陷）。
 */
class MenuCommandHandlerTest {

    private static final long CHAT = -100L;

    @BotCommand(value = "words", description = "查看本群违禁词（需管理员权限）",
            requiredPermission = Permission.MANAGE_CONFIG)
    static class WordsHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "词表");
        }
    }

    @BotCommand(value = "enable", description = "开启", requiredPermission = Permission.MANAGE_CONFIG,
            worksWhenDisabled = true)
    static class EnableHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "已开启");
        }
    }

    @BotCommand(value = "echo", description = "回显")
    static class EchoHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "pong");
        }
    }

    @BotCommand(value = "menu", description = "面板")
    static class MenuHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "menu");
        }
    }

    private final GroupConfigService groupConfigs = mock(GroupConfigService.class);

    /** 生产里注册表由「全部 CommandHandler（含本处理器）」构造，故只能经惰性句柄注入——这里用 mock 复刻同一形状。 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<CommandRegistry> providerOf(CommandRegistry registry) {
        ObjectProvider<CommandRegistry> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(registry);
        return provider;
    }

    private MenuCommandHandler handler(String role, boolean groupEnabled) {
        CommandRegistry registry = new CommandRegistry(List.of(
                new WordsHandler(), new EnableHandler(), new EchoHandler(), new MenuHandler()));
        when(groupConfigs.findOrDefault(CHAT)).thenReturn(new GroupConfigView(CHAT, "群", groupEnabled));
        return new MenuCommandHandler(providerOf(registry),
                new PermissionChecker((c, u) -> Role.valueOf(role)), groupConfigs);
    }

    private static String textOf(BotApiMethod<?> method) {
        return ((SendMessage) method).getText();
    }

    /** data 里的字符数（UTF-8 下 ASCII 即字节数）——用于确认不超 Telegram 的 64 上限。 */
    private static List<String> dataOf(BotApiMethod<?> method) {
        SendMessage msg = (SendMessage) method;
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) msg.getReplyMarkup();
        return markup.getKeyboard().stream().flatMap(List::stream).map(b -> b.getCallbackData()).toList();
    }

    @Test
    void listsOnlyManagementCommandsTheUserCanUse() {
        BotApiMethod<?> reply = handler("ADMIN", true).handle(new UpdateContext(1, 42L, CHAT, "menu"));

        List<String> data = dataOf(reply);
        assertThat(data).as("有 MANAGE_CONFIG 的应出现").contains("menu:" + CHAT + ":words");
        assertThat(data).as("面板自身不列").noneMatch(d -> d.endsWith(":menu"));
        assertThat(data).as("无门槛的普通命令不该混进管理面板").noneMatch(d -> d.endsWith(":echo"));
    }

    /** 用户拍板：非授权者回一句说明，而不是空卡或静默。 */
    @Test
    void tellsUnauthorizedUserInsteadOfShowingEmptyPanel() {
        BotApiMethod<?> reply = handler("MEMBER", true).handle(new UpdateContext(1, 99L, CHAT, "menu"));

        assertThat(((SendMessage) reply).getReplyMarkup()).as("无权时不应给键盘").isNull();
        assertThat(textOf(reply)).isEqualTo(MenuCommandHandler.NO_PERMISSION);
    }

    /** 停用群：管理命令都不该出现，但 /enable 必须在——否则该群永久锁死。 */
    @Test
    void keepsRecoveryCommandInDisabledGroup() {
        BotApiMethod<?> reply = handler("ADMIN", false).handle(new UpdateContext(1, 42L, CHAT, "menu"));

        List<String> data = dataOf(reply);
        assertThat(data).as("停用群里 /enable 必须仍可见").contains("menu:" + CHAT + ":enable");
        assertThat(data).as("非恢复类命令在停用群不该出现").noneMatch(d -> d.endsWith(":words"));
    }

    /** 按钮上的文字（用户直接看到的东西）。 */
    private static List<String> labelsOf(BotApiMethod<?> method) {
        SendMessage msg = (SendMessage) method;
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) msg.getReplyMarkup();
        return markup.getKeyboard().stream().flatMap(List::stream).map(b -> b.getText()).toList();
    }

    /**
     * 用户原话级的需求：「/menu 弹出的所有命令没有详细信息，交互不太友好」。
     *
     * <p>所以按钮必须自己说清「点它会做什么」，而不是只放一个 {@code /words} 让人猜。
     */
    @Test
    void buttonsExplainWhatEachCommandDoes() {
        BotApiMethod<?> reply = handler("ADMIN", true).handle(new UpdateContext(1, 42L, CHAT, "menu"));

        assertThat(labelsOf(reply))
                .as("按钮文案必须包含该命令的说明，而不是只有命令名")
                .anySatisfy(label -> assertThat(label).contains("查看本群违禁词"));
    }

    /**
     * 按钮上不该再带「（需管理员权限）」这类括注：菜单**已经**按当前用户的权限过滤过了，
     * 再写一遍既是噪声、又把按钮撑长（调研：按钮文本过长会换行错乱、部分客户端截断）。
     */
    @Test
    void buttonsDropPermissionParenthetical() {
        BotApiMethod<?> reply = handler("ADMIN", true).handle(new UpdateContext(1, 42L, CHAT, "menu"));

        assertThat(labelsOf(reply))
                .as("权限括注在菜单里是冗余信息，应剔除")
                .noneMatch(label -> label.contains("需管理员权限"))
                .allSatisfy(label -> assertThat(label).as("仍要保留命令名，便于以后直接输入")
                        .startsWith("/"));
    }

    /** 没写描述的注册项不能因此变成空按钮——退化为只显示命令名。 */
    @Test
    void commandWithoutDescriptionFallsBackToBareName() {
        CommandRegistry registry = new CommandRegistry(List.of(new NoDescriptionHandler()));
        when(groupConfigs.findOrDefault(CHAT)).thenReturn(new GroupConfigView(CHAT, "群", true));
        MenuCommandHandler withBare = new MenuCommandHandler(providerOf(registry),
                new PermissionChecker((c, u) -> Role.ADMIN), groupConfigs);

        BotApiMethod<?> reply = withBare.handle(new UpdateContext(1, 42L, CHAT, "menu"));

        assertThat(labelsOf(reply)).containsExactly("/nodesc");
    }

    @BotCommand(value = "nodesc", description = "", requiredPermission = Permission.MANAGE_CONFIG)
    static class NoDescriptionHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "x");
        }
    }

    @Test
    void callbackDataStaysWithinTelegramLimit() {
        BotApiMethod<?> reply = handler("ADMIN", true).handle(new UpdateContext(1, 42L, CHAT, "menu"));

        assertThat(dataOf(reply)).allSatisfy(d ->
                assertThat(d.length()).as("callback_data 上限 64 字节").isLessThanOrEqualTo(64));
    }
}

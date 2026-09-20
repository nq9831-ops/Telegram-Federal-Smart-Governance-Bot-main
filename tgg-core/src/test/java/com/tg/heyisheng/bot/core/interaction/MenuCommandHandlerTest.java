package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /menu} 主页渲染测试（v2：分类子菜单）。
 *
 * <p>三个要点：① 主页只列**非空分类**，不列命令本身；② 谁都无权时不列空卡、回一句说明；
 * ③ 停用群里「群设置」分类仍在（含 {@code /enable}）——否则该群永久锁死（项目实测过的缺陷）。
 *
 * <p>可见性判定与命令分组本身在 {@code MenuCatalogTest}；按钮文案与 data 形状在
 * {@code MenuViewTest}。本类只钉「文本命令入口渲染出正确的主页」这一件事。
 */
class MenuCommandHandlerTest {

    private static final long CHAT = -100L;

    @BotCommand(value = "words", description = "查看本群违禁词（需管理员权限）",
            requiredPermission = Permission.MANAGE_CONFIG, category = MenuCategory.MODERATION)
    static class WordsHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "词表");
        }
    }

    @BotCommand(value = "enable", description = "开启本群自动化能力（需管理员权限）",
            requiredPermission = Permission.MANAGE_CONFIG, worksWhenDisabled = true,
            category = MenuCategory.GROUP)
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
        MenuCatalog catalog = new MenuCatalog(providerOf(registry),
                new PermissionChecker((chatId, userId) -> Role.valueOf(role)), List.of());
        return new MenuCommandHandler(catalog, groupConfigs);
    }

    private static SendMessage messageOf(BotApiMethod<?> method) {
        return (SendMessage) method;
    }

    /** 主页上的分类导航 data。 */
    private static List<String> dataOf(BotApiMethod<?> method) {
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) messageOf(method).getReplyMarkup();
        return markup.getKeyboard().stream().flatMap(List::stream)
                .map(b -> b.getCallbackData()).toList();
    }

    private static List<String> labelsOf(BotApiMethod<?> method) {
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) messageOf(method).getReplyMarkup();
        return markup.getKeyboard().stream().flatMap(List::stream).map(b -> b.getText()).toList();
    }

    @Test
    void listsOnlyCategoriesWithContent() {
        BotApiMethod<?> reply = handler("ADMIN", true).handle(new UpdateContext(1, 42L, CHAT, "menu"));

        assertThat(dataOf(reply)).as("主页列分类而非命令")
                .containsExactly("menu:" + CHAT + ":nav:moderation", "menu:" + CHAT + ":nav:group");
        assertThat(labelsOf(reply)).containsExactly("内容审核（1）", "群设置（1）");
        assertThat(dataOf(reply)).as("普通命令不该混进管理面板")
                .noneMatch(d -> d.contains("echo"))
                .noneMatch(d -> d.endsWith(":menu"));
    }

    /** 用户拍板：非授权者回一句说明，而不是空卡或静默。 */
    @Test
    void tellsUnauthorizedUserInsteadOfShowingEmptyPanel() {
        BotApiMethod<?> reply = handler("MEMBER", true).handle(new UpdateContext(1, 99L, CHAT, "menu"));

        assertThat(messageOf(reply).getReplyMarkup()).as("无权时不应给键盘").isNull();
        assertThat(messageOf(reply).getText()).isEqualTo(MenuCommandHandler.NO_PERMISSION);
    }

    /** 停用群：非恢复类分类都不该出现，但含 /enable 的「群设置」必须在——否则该群永久锁死。 */
    @Test
    void keepsRecoveryCategoryInDisabledGroup() {
        BotApiMethod<?> reply = handler("ADMIN", false).handle(new UpdateContext(1, 42L, CHAT, "menu"));

        assertThat(dataOf(reply)).as("停用群里只剩含 /enable 的分类").containsExactly("menu:" + CHAT + ":nav:group");
        assertThat(messageOf(reply).getText()).isEqualTo(MenuView.homeText());
    }

    @Test
    void callbackDataStaysWithinTelegramLimit() {
        BotApiMethod<?> reply = handler("ADMIN", true).handle(new UpdateContext(1, 42L, CHAT, "menu"));

        assertThat(dataOf(reply)).allSatisfy(d ->
                assertThat(d.length()).as("callback_data 上限 64 字节").isLessThanOrEqualTo(64));
    }
}

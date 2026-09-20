package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MenuCatalog} 的可见性与分组：面板「给谁看什么」的全部判定都在这里。
 *
 * <p>三个不变量：① 接缝命令只对该接缝的白名单可见；② 无权限点的命令**一律**不进面板
 * （保持升级前语义，也不能因接缝机制而对所有人放行）；③ 停用群只剩「恢复类」命令。
 */
class MenuCatalogTest {

    private static final long CHAT = -100L;
    private static final long ADMIN = 42L;
    private static final long MEMBER = 99L;
    private static final long REVIEWER = 777L;

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

    /** 平台白名单类：注解无权限点，可见性由接缝给出。 */
    @BotCommand(value = "review_list", description = "列出待复核的审核命中（复核人）",
            category = MenuCategory.REVIEW)
    static class ReviewListHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "队列");
        }
    }

    /** 个人命令：无权限点、也无接缝——不该出现在管理面板。 */
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

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CommandRegistry> providerOf(CommandRegistry registry) {
        ObjectProvider<CommandRegistry> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(registry);
        return provider;
    }

    /** 只认 {@code REVIEWER} 的接缝；{@code null} 身份一律不可见。 */
    private static MenuVisibility reviewerSeam(Set<String> commands) {
        return new MenuVisibility() {
            @Override
            public Set<String> commands() {
                return commands;
            }

            @Override
            public boolean visible(long chatId, Long userId) {
                return userId != null && userId == REVIEWER;
            }
        };
    }

    private MenuCatalog catalog(List<MenuVisibility> seams) {
        CommandRegistry registry = new CommandRegistry(List.of(
                new WordsHandler(), new EnableHandler(), new ReviewListHandler(),
                new EchoHandler(), new MenuHandler()));
        return new MenuCatalog(providerOf(registry),
                new PermissionChecker((chatId, userId) -> {
                    // REVIEWER 在本测试里同时是群管理员——现实中复核人常常也是某群管理员
                    if (userId != null && (userId == ADMIN || userId == REVIEWER)) {
                        return Role.ADMIN;
                    }
                    return Role.MEMBER;
                }), seams);
    }

    @Test
    void seamCommandIsVisibleOnlyToSeamMembers() {
        MenuCatalog catalog = catalog(List.of(reviewerSeam(Set.of("review_list"))));

        assertThat(catalog.visibleCommands(CHAT, REVIEWER, true)).contains("review_list");
        assertThat(catalog.visibleCommands(CHAT, ADMIN, true))
                .as("群管理员不是平台复核人——接缝命令不得对他出现")
                .doesNotContain("review_list");
        assertThat(catalog.visibleCommands(CHAT, MEMBER, true)).doesNotContain("review_list");
    }

    @Test
    void unseamedCommandWithoutPermissionPointStaysOut() {
        MenuCatalog catalog = catalog(List.of(reviewerSeam(Set.of("review_list"))));

        assertThat(catalog.visibleCommands(CHAT, ADMIN, true))
                .as("无权限点、无接缝的命令（echo/menu）不进管理面板——保持升级前语义")
                .doesNotContain("echo", "menu");
    }

    @Test
    void rbacCommandFollowsGroupPermission() {
        MenuCatalog catalog = catalog(List.of());

        assertThat(catalog.visibleCommands(CHAT, ADMIN, true)).contains("words");
        assertThat(catalog.visibleCommands(CHAT, MEMBER, true)).doesNotContain("words");
    }

    @Test
    void disabledGroupKeepsOnlyRecoveryCommands() {
        MenuCatalog catalog = catalog(List.of(reviewerSeam(Set.of("review_list"))));

        assertThat(catalog.visibleCommands(CHAT, ADMIN, false))
                .as("停用群里只有 worksWhenDisabled 的命令（/enable）")
                .contains("enable")
                .doesNotContain("words", "review_list");
    }

    @Test
    void groupsByCategoryInDeclarationOrder() {
        MenuCatalog catalog = catalog(List.of(reviewerSeam(Set.of("review_list"))));

        assertThat(catalog.grouped(CHAT, REVIEWER, true).keySet())
                .as("分类按枚举声明顺序：内容审核 → 群设置 → 复核合规")
                .containsExactly(MenuCategory.MODERATION, MenuCategory.GROUP, MenuCategory.REVIEW);
        assertThat(catalog.grouped(CHAT, REVIEWER, true).get(MenuCategory.REVIEW))
                .containsExactly("review_list");
    }

    @Test
    void forbiddenCommandsContributeNoCategoryAtAll() {
        MenuCatalog catalog = catalog(List.of());

        assertThat(catalog.grouped(CHAT, MEMBER, true))
                .as("普通成员无权时不应出现任何空分类（空键盘不如回一句说明）")
                .isEmpty();
    }

    @Test
    void duplicateSeamRegistrationFailsAtConstruction() {
        MenuVisibility first = reviewerSeam(Set.of("review_list"));
        MenuVisibility second = reviewerSeam(Set.of("review_list"));

        assertThatThrownBy(() -> catalog(List.of(first, second)))
                .as("两条接缝声称负责同一命令属配置错误，装配期即失败")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("review_list");
    }

    @Test
    void descriptionsComeFromRegistry() {
        assertThat(catalog(List.of()).descriptions()).containsKey("words");
    }
}

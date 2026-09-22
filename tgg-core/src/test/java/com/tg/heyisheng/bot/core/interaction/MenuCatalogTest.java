package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.Role;
import com.tg.heyisheng.bot.core.permission.InMemoryRoleSource;
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

    /** 自助命令：无权限点但声明 publicCommand → 对全体成员可见（客户端 / 菜单已收敛，它是唯一发现路径）。 */
    @BotCommand(value = "quiet_hours", description = "设置免打扰时段", publicCommand = true,
            category = MenuCategory.SELF_SERVICE)
    static class QuietHoursHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "ok");
        }
    }

    /** 群限定命令：handler 内 chatId>=0 硬拒（GROUP_ONLY），声明 groupOnly 供展示层分场景。 */
    @BotCommand(value = "teach", description = "教一条本群审核规则（需 TEACH_RULE）",
            requiredPermission = Permission.TEACH_RULE, groupOnly = true,
            category = MenuCategory.MODERATION)
    static class TeachHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "教好了");
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
        return new MenuCatalog(providerOf(registry), new PermissionChecker(roleSource()), seams);
    }

    /** 真实授权源：ADMIN 与 REVIEWER 在本测试里都是该群管理员（现实中复核人常也是某群管理员）。 */
    private static InMemoryRoleSource roleSource() {
        InMemoryRoleSource source = new InMemoryRoleSource();
        source.assign(CHAT, ADMIN, Role.ADMIN);
        source.assign(CHAT, REVIEWER, Role.ADMIN);
        return source;
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
                .as("无权限点、又未声明 publicCommand 的命令（echo/menu）不进面板——fail-closed")
                .doesNotContain("echo", "menu");
    }

    /** 自助命令（无权限点 + 声明 publicCommand）对**全体成员**可见——它是客户端菜单之外的发现路径。 */
    @Test
    void selfServiceCommandIsVisibleToEveryone() {
        CommandRegistry registry = new CommandRegistry(List.of(
                new QuietHoursHandler(), new WordsHandler(), new MenuHandler()));
        MenuCatalog catalog = new MenuCatalog(providerOf(registry),
                new PermissionChecker(roleSource()), List.of());

        assertThat(catalog.visibleCommands(CHAT, MEMBER, true))
                .as("普通成员也应看到自助命令")
                .contains("quiet_hours");
        assertThat(catalog.grouped(CHAT, MEMBER, true))
                .as("自助命令归入「自助功能」分类")
                .containsKey(MenuCategory.SELF_SERVICE);
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

    /**
     * 群限定命令在私聊里不进「可用」列表——handler 的 chatId>=0 硬门只会回 GROUP_ONLY，
     * 面板照列就是「点了却无声」（MenuCatalog javadoc 点名的漂移形态）。群聊照常。
     */
    @Test
    void groupOnlyCommandStaysOutOfPrivateUsableList() {
        InMemoryRoleSource source = new InMemoryRoleSource();
        source.assign(ADMIN, ADMIN, Role.ADMIN);   // 私聊上下文的授权（chatId == userId）
        source.assign(CHAT, ADMIN, Role.ADMIN);    // 同一人在群里的授权
        MenuCatalog catalog = new MenuCatalog(providerOf(new CommandRegistry(List.of(
                new TeachHandler(), new MenuHandler()))),
                new PermissionChecker(source), List.of());

        assertThat(catalog.visibleCommands(ADMIN, ADMIN, true))
                .as("私聊里群限定命令不可用——不进「私聊可用」列表")
                .doesNotContain("teach");
        assertThat(catalog.visibleCommands(CHAT, ADMIN, true))
                .as("群聊里照常可见")
                .contains("teach");
    }

    /** 「这些得到群里用」的存在量词判据：在**任一群**有对应权限即列出（数据源与执行门同一份 grants）。 */
    @Test
    void groupBoundCommandsUseGrantsFromAnyGroup() {
        InMemoryRoleSource source = new InMemoryRoleSource();
        source.assign(CHAT, ADMIN, Role.ADMIN);
        MenuCatalog catalog = new MenuCatalog(providerOf(new CommandRegistry(List.of(
                new TeachHandler(), new MenuHandler()))),
                new PermissionChecker(source), List.of());

        assertThat(catalog.groupBoundCommands(ADMIN))
                .as("他在群里有 TEACH_RULE——私聊面板应提示「这些得到群里用」")
                .containsExactly("teach");
        assertThat(catalog.visibleCommands(ADMIN, ADMIN, true))
                .as("但私聊里仍不进可用列表")
                .doesNotContain("teach");
    }

    /** 无授权、无身份都 fail-closed：不列、不猜。MODERATOR 只有 BAN_USER，教规则要 TEACH_RULE。 */
    @Test
    void groupBoundCommandsFailClosedWithoutGrants() {
        InMemoryRoleSource source = new InMemoryRoleSource();
        source.assign(CHAT, MEMBER, Role.MODERATOR);
        MenuCatalog catalog = new MenuCatalog(providerOf(new CommandRegistry(List.of(
                new TeachHandler(), new MenuHandler()))),
                new PermissionChecker(source), List.of());

        assertThat(catalog.groupBoundCommands(MEMBER)).isEmpty();
        assertThat(catalog.groupBoundCommands(null)).isEmpty();
    }

    /** 策展（反向接缝）：对指定查看者收走命令——只影响他的展示，不影响其他人。 */
    @Test
    void curatorHidesCommandOnlyFromItsTargetViewers() {
        MenuCurator curator = new MenuCurator() {
            @Override
            public Set<String> commands() {
                return Set.of("quiet_hours");
            }

            @Override
            public boolean hides(long chatId, Long userId) {
                return userId != null && userId == ADMIN;
            }
        };
        MenuCatalog catalog = new MenuCatalog(providerOf(new CommandRegistry(List.of(
                new QuietHoursHandler(), new MenuHandler()))),
                new PermissionChecker(roleSource()), List.of(), List.of(curator));

        assertThat(catalog.visibleCommands(CHAT, ADMIN, true))
                .as("策展目标：命令从他的面上收走")
                .doesNotContain("quiet_hours");
        assertThat(catalog.visibleCommands(CHAT, MEMBER, true))
                .as("其他人视角不变")
                .contains("quiet_hours");
    }

    /** 策展 fail-open：身份不明不收（收起是策展不是安全门——误藏比多显示更坏）。 */
    @Test
    void curatorKeepsCommandVisibleForUnidentifiedViewer() {
        MenuCurator curator = new MenuCurator() {
            @Override
            public Set<String> commands() {
                return Set.of("quiet_hours");
            }

            @Override
            public boolean hides(long chatId, Long userId) {
                return userId != null && userId == ADMIN;
            }
        };
        MenuCatalog catalog = new MenuCatalog(providerOf(new CommandRegistry(List.of(
                new QuietHoursHandler(), new MenuHandler()))),
                new PermissionChecker(roleSource()), List.of(), List.of(curator));

        assertThat(catalog.visibleCommands(CHAT, null, true)).contains("quiet_hours");
    }

    /** 两条策展认领同一命令属配置错误，装配期即失败（同接缝口径）。 */
    @Test
    void duplicateCuratorRegistrationFailsAtConstruction() {
        MenuCurator first = new MenuCurator() {
            @Override
            public Set<String> commands() {
                return Set.of("quiet_hours");
            }

            @Override
            public boolean hides(long chatId, Long userId) {
                return false;
            }
        };
        MenuCurator second = new MenuCurator() {
            @Override
            public Set<String> commands() {
                return Set.of("quiet_hours");
            }

            @Override
            public boolean hides(long chatId, Long userId) {
                return false;
            }
        };

        assertThatThrownBy(() -> new MenuCatalog(providerOf(new CommandRegistry(List.of(
                new QuietHoursHandler()))), new PermissionChecker(roleSource()),
                List.of(), List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quiet_hours");
    }
}

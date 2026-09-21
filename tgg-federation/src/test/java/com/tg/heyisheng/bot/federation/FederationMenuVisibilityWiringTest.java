package com.tg.heyisheng.bot.federation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.core.dispatch.CommandMenuRegistrar;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigRepository;
import com.tg.heyisheng.bot.core.interaction.MenuCatalog;
import com.tg.heyisheng.bot.core.interaction.MenuVisibility;
import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>跨模块接线</b>测试（模块九 §7.4 可见性接缝 · 联邦部分）：模块八注册的接缝必须真的被 core 的
 * {@link MenuCatalog} 用起来。
 *
 * <p>两端各自的单测都绿、中间断链照样静默失效——故此处同时加载真实的 {@code FederationConfiguration}
 * 与真实的 {@link MenuCatalog}，走一遍完整装配。
 */
class FederationMenuVisibilityWiringTest {

    private static final long CHAT = -100900999L;
    private static final long ADMIN = 4242L;
    private static final long OTHER = 999L;
    private static final String NODE_SPEC = "https://peer.example.com|" + publicKeyBase64();

    private static String publicKeyBase64() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FederationConfiguration.class, RuntimeConfigTestStub.class)
            .withUserConfiguration(ApproveAppealCommandHandler.class, RejectAppealCommandHandler.class,
                    PendingAppealsCommandHandler.class, AppealCommandHandler.class)
            .withBean(GroupConfigRepository.class, () -> mock(GroupConfigRepository.class))
            // ApplicationContextRunner 不加载 JPA，仓库需手工补（同 FederationWiringTest 范式）
            .withBean(FederationPenaltyRepository.class, () -> mock(FederationPenaltyRepository.class))
            .withBean(FederationAppealRepository.class, () -> mock(FederationAppealRepository.class))
            .withBean(ModerationActionSender.class, ModerationActionSender::noop)
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CommandRegistry> providerOf(CommandRegistry registry) {
        ObjectProvider<CommandRegistry> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(registry);
        return provider;
    }

    private static CommandRegistry registryOf(ApplicationContext context) {
        return new CommandRegistry(List.of(
                context.getBean(ApproveAppealCommandHandler.class),
                context.getBean(RejectAppealCommandHandler.class),
                context.getBean(PendingAppealsCommandHandler.class),
                context.getBean(AppealCommandHandler.class)));
    }

    /** 只读展示：不涉及群内权限，故权限判定恒为普通成员——可见性完全由接缝决定。 */
    private static MenuCatalog catalogWith(CommandRegistry registry, MenuVisibility seam) {
        return new MenuCatalog(providerOf(registry),
                new PermissionChecker(Role.MEMBER), List.of(seam));
    }

    @Test
    void disabledFederationModuleContributesNoSeam() {
        runner.run(context -> assertThat(context).doesNotHaveBean(MenuVisibility.class));
    }

    @Test
    void enabledFederationModuleRegistersTheSeamForItsWhitelistCommands() {
        runner.withPropertyValues("tgg.federation.enabled=true",
                        "tgg.federation.nodes=" + NODE_SPEC,
                        "tgg.federation.admins=" + ADMIN)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MenuVisibility.class);

                    MenuVisibility seam = context.getBean(MenuVisibility.class);
                    assertThat(seam.commands()).containsExactlyInAnyOrder("approve", "reject", "pending");
                    assertThat(seam.visible(CHAT, ADMIN)).isTrue();
                    assertThat(seam.visible(CHAT, OTHER)).isFalse();
                    assertThat(seam.visible(CHAT, null)).as("身份不可识别一律不可见").isFalse();
                });
    }

    /**
     * 联邦模块的两类命令各有归属：{@code /appeal} 是**成员自助**（{@code publicCommand} → 经
     * {@code /menu} 面板呈现），三条裁决命令是**平台白名单**（只对联邦管理员可见）——两者不能混。
     *
     * <p>客户端 {@code /} 菜单现已收敛为**只留 {@code /menu} 一个入口**，故这两类命令**都不**出现在
     * {@code planMenus} 的任何档里；{@code /appeal} 靠面板对所有人可见，裁决命令靠接缝只对管理员可见。
     *
     * <p>这条不变量覆盖了 {@code appeal} 的 {@code publicCommand} 声明：federation 模块开关在
     * {@code CommandMenuContentTest} 里默认关闭，若不在这里校验，那条声明删掉也不会有测试变红。
     */
    @Test
    void appealIsSelfServiceWhileRulingsAreSeamed() {
        runner.withPropertyValues("tgg.federation.enabled=true",
                        "tgg.federation.nodes=" + NODE_SPEC,
                        "tgg.federation.admins=" + ADMIN)
                .run(context -> {
                    CommandRegistry registry = registryOf(context);
                    List<CommandMenuRegistrar.ScopedMenu> menus = CommandMenuRegistrar.planMenus(
                            registry, context.getBean(MenuVisibility.class).commands(), List.of());

                    assertThat(menus).allSatisfy(menu -> assertThat(namesOf(menu))
                            .as("档 %s 不得含任何联邦命令：客户端菜单只留 /menu 入口", menu.label())
                            .doesNotContain("appeal", "approve", "reject", "pending"));

                    // /appeal 是自助命令 → 对普通成员可见（经面板）；三条裁决命令只对联邦管理员可见
                    MenuCatalog catalog = catalogWith(registry, context.getBean(MenuVisibility.class));
                    assertThat(catalog.visibleCommands(CHAT, OTHER, true))
                            .as("/appeal 是成员自助，普通成员可用；裁决命令不得出现")
                            .contains("appeal")
                            .doesNotContain("approve", "reject", "pending");
                });
    }

    /** 取某一档的命令名——绕开 TelegramBots {@code BotCommand} 的类型名。 */
    private static List<String> namesOf(CommandMenuRegistrar.ScopedMenu menu) {
        return menu.commands().stream()
                .map(org.telegram.telegrambots.meta.api.objects.commands.BotCommand::getCommand)
                .toList();
    }

    /** 端到端接线：真实接缝 + 真实 core 聚合器 → 联邦管理员看到「复核合规」分类，其他人看不到。 */
    @Test
    void adminSeesReviewCategoryThroughTheRealComposition() {
        runner.withPropertyValues("tgg.federation.enabled=true",
                        "tgg.federation.nodes=" + NODE_SPEC,
                        "tgg.federation.admins=" + ADMIN)
                .run(context -> {
                    MenuCatalog catalog = catalogWith(registryOf(context),
                            context.getBean(MenuVisibility.class));

                    Map<MenuCategory, List<String>> forAdmin = catalog.grouped(CHAT, ADMIN, true);
                    assertThat(forAdmin).containsKey(MenuCategory.REVIEW);
                    assertThat(forAdmin.get(MenuCategory.REVIEW))
                            .containsExactlyInAnyOrder("approve", "reject", "pending");

                    assertThat(catalog.grouped(CHAT, OTHER, true))
                            .as("非联邦管理员的菜单里不得出现裁决命令——不暴露其存在")
                            .doesNotContainKey(MenuCategory.REVIEW);
                });
    }
}

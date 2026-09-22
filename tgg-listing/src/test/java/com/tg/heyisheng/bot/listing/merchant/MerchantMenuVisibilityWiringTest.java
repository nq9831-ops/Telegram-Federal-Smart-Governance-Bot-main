package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.interaction.MenuCatalog;
import com.tg.heyisheng.bot.core.interaction.MenuVisibility;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.Role;
import com.tg.heyisheng.bot.listing.command.MerchantApplyCommandHandler;
import com.tg.heyisheng.bot.listing.command.MerchantDepositCommandHandler;
import com.tg.heyisheng.bot.listing.command.MerchantExitCommandHandler;
import com.tg.heyisheng.bot.listing.command.MerchantReviewCommandHandler;
import com.tg.heyisheng.bot.listing.command.MerchantSettleCommandHandler;
import com.tg.heyisheng.bot.listing.command.MerchantStatusCommandHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>跨模块接线</b>测试（模块九 §7.4 可见性接缝）：模块六注册的接缝必须真的被 core 的
 * {@link MenuCatalog} 用起来。
 *
 * <p><b>为什么不能只靠两端各自的单测</b>：core 的聚合器测过、本模块的接缝也测过，
 * 但「Spring 会不会把 {@code MerchantMenuVisibility} 收进 {@code List<MenuVisibility>}」
 * 是<b>两端之间</b>的事——两端单测都绿、中间断链照样静默失效
 * （同 {@code TeachGateCompositionWiringTest} 的立项理由）。
 *
 * <p>聚合器用**真实实现**而非复刻 stub：这里 new 的是真实的 {@link MenuCatalog}，
 * 接缝也是容器里真实的那一个——复刻 stub 会在 core 改实现时静默漂移，等于白测。
 */
class MerchantMenuVisibilityWiringTest {

    private static final long CHAT = -100900999L;
    private static final long REVIEWER = 888001L;
    private static final long OTHER = 999L;

    /** 受同一开关门控的仓库替身（runner 不加载 JPA）。 */
    @Configuration
    @ConditionalOnProperty(prefix = "tgg.merchant", name = "enabled", havingValue = "true")
    static class RepositoryStub {

        @Bean
        MerchantRepository merchantRepository() {
            return mock(MerchantRepository.class);
        }

        @Bean
        MerchantDepositRepository merchantDepositRepository() {
            return mock(MerchantDepositRepository.class);
        }

        @Bean
        MerchantDepositRecordRepository merchantDepositRecordRepository() {
            return mock(MerchantDepositRecordRepository.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MerchantConfiguration.class, RepositoryStub.class,
                    RuntimeConfigTestStub.class,
                    MerchantApplyCommandHandler.class, MerchantReviewCommandHandler.class,
                    MerchantStatusCommandHandler.class, MerchantExitCommandHandler.class,
                    MerchantDepositCommandHandler.class, MerchantSettleCommandHandler.class);

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CommandRegistry> providerOf(CommandRegistry registry) {
        ObjectProvider<CommandRegistry> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(registry);
        return provider;
    }

    /** 只读展示：不涉及群内权限，故权限判定恒为普通成员——可见性完全由接缝决定。 */
    private static MenuCatalog catalogWith(CommandRegistry registry, MenuVisibility seam) {
        return new MenuCatalog(providerOf(registry),
                new PermissionChecker(Role.MEMBER), List.of(seam));
    }

    private static CommandRegistry registryOf(org.springframework.context.ApplicationContext context) {
        return new CommandRegistry(List.of(
                context.getBean(MerchantReviewCommandHandler.class),
                context.getBean(MerchantDepositCommandHandler.class),
                context.getBean(MerchantSettleCommandHandler.class)));
    }

    /** 门关：模块不启用时不该存在任何接缝（那些命令本就不在容器里）。 */
    @Test
    void disabledMerchantModuleContributesNoSeam() {
        runner.run(context -> assertThat(context).doesNotHaveBean(MenuVisibility.class));
    }

    @Test
    void enabledMerchantModuleRegistersTheSeamForItsWhitelistCommands() {
        runner.withPropertyValues("tgg.merchant.enabled=true", "tgg.merchant.reviewers=" + REVIEWER)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MenuVisibility.class);

                    MenuVisibility seam = context.getBean(MenuVisibility.class);
                    assertThat(seam.commands()).containsExactlyInAnyOrder(
                            "merchant_review", "merchant_deposit", "merchant_settle");
                    assertThat(seam.visible(CHAT, REVIEWER)).isTrue();
                    assertThat(seam.visible(CHAT, OTHER)).isFalse();
                    assertThat(seam.visible(CHAT, null)).as("身份不可识别一律不可见").isFalse();
                });
    }

    /** 端到端接线：真实接缝 + 真实 core 聚合器 → 复核人看到「商家收录」分类，非复核人看不到（2026-09-22 拆分后归 MERCHANT）。 */
    @Test
    void reviewerSeesMerchantCategoryThroughTheRealComposition() {
        runner.withPropertyValues("tgg.merchant.enabled=true", "tgg.merchant.reviewers=" + REVIEWER)
                .run(context -> {
                    MenuCatalog catalog = catalogWith(registryOf(context),
                            context.getBean(MenuVisibility.class));

                    Map<MenuCategory, List<String>> forReviewer = catalog.grouped(CHAT, REVIEWER, true);
                    assertThat(forReviewer).containsKey(MenuCategory.MERCHANT);
                    assertThat(forReviewer.get(MenuCategory.MERCHANT)).containsExactlyInAnyOrder(
                            "merchant_review", "merchant_deposit", "merchant_settle");

                    assertThat(catalog.grouped(CHAT, OTHER, true))
                            .as("非复核人的面板里不得出现这些命令——不暴露其存在")
                            .doesNotContainKey(MenuCategory.MERCHANT);
                });
    }
}

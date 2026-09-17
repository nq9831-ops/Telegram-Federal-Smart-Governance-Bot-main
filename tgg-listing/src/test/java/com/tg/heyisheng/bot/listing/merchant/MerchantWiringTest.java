package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.listing.command.MerchantApplyCommandHandler;
import com.tg.heyisheng.bot.listing.command.MerchantReviewCommandHandler;
import com.tg.heyisheng.bot.listing.command.MerchantStatusCommandHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 模块六装配门测试：断言开关的<b>两个方向</b>。
 *
 * <p>只测「关闭 → 无 bean」或只测「开启 → 有 bean」都等于没测——前者的实现可以永远不装配，
 * 后者的实现可以永远装配。两侧都断言，门控才成立。
 *
 * <p>⚠️ {@code ApplicationContextRunner} 不加载 JPA，Spring Data 不会创建仓库代理，故用
 * {@link RepositoryStub} 补一个仓库替身——<b>关键是替身自身也受同一个开关门控</b>，
 * 否则「关闭方向」会被替身污染而失去意义（与 {@code ListingWiringTest} 同款纪律）。
 *
 * <p>三个命令处理器靠 {@code @BotCommand}（元注解 {@code @Component}）进组件扫描；
 * {@code ApplicationContextRunner} 不做扫描，故在此显式注册——它们自持
 * {@code @ConditionalOnProperty}，门的两个方向照样被断言。
 */
class MerchantWiringTest {

    /** 受同一开关门控的仓库替身，用于让「关闭 → 无仓库 bean」这一断言成立。 */
    @Configuration
    @ConditionalOnProperty(prefix = "tgg.merchant", name = "enabled", havingValue = "true")
    static class RepositoryStub {

        @Bean
        MerchantRepository merchantRepository() {
            return mock(MerchantRepository.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MerchantConfiguration.class, RepositoryStub.class,
                    MerchantApplyCommandHandler.class, MerchantReviewCommandHandler.class,
                    MerchantStatusCommandHandler.class);

    @Test
    void disabledByDefaultProducesNoMerchantBeans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(MerchantProperties.class);
            assertThat(context).doesNotHaveBean(MerchantService.class);
            assertThat(context).doesNotHaveBean(MerchantReviewGuard.class);
            assertThat(context).doesNotHaveBean(MerchantApplyCommandHandler.class);
            assertThat(context).doesNotHaveBean(MerchantReviewCommandHandler.class);
            assertThat(context).doesNotHaveBean(MerchantStatusCommandHandler.class);
        });
    }

    @Test
    void explicitlyDisabledAlsoProducesNoMerchantBeans() {
        runner.withPropertyValues("tgg.merchant.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(MerchantProperties.class);
            assertThat(context).doesNotHaveBean(MerchantService.class);
            assertThat(context).doesNotHaveBean(MerchantReviewGuard.class);
            assertThat(context).doesNotHaveBean(MerchantApplyCommandHandler.class);
            assertThat(context).doesNotHaveBean(MerchantReviewCommandHandler.class);
            assertThat(context).doesNotHaveBean(MerchantStatusCommandHandler.class);
        });
    }

    @Test
    void enabledWiresServiceGuardAndCommands() {
        runner.withPropertyValues("tgg.merchant.enabled=true", "tgg.merchant.reviewers=888001")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MerchantProperties.class);
                    assertThat(context).hasSingleBean(MerchantService.class);
                    assertThat(context).hasSingleBean(MerchantReviewGuard.class);
                    assertThat(context).hasSingleBean(MerchantApplyCommandHandler.class);
                    assertThat(context).hasSingleBean(MerchantReviewCommandHandler.class);
                    assertThat(context).hasSingleBean(MerchantStatusCommandHandler.class);

                    assertThat(context.getBean(MerchantProperties.class).getInitialScore()).isEqualTo(500);
                    assertThat(context.getBean(MerchantReviewGuard.class).isReviewer(888001L))
                            .as("配置里的复核人应真的进入白名单")
                            .isTrue();
                });
    }

    @Test
    void enablingMerchantWithoutCreditModuleStartsCleanly() {
        // 模块六与模块七是两个独立开关：只开商家不应因「没有 CreditService bean」而启动失败
        runner.withPropertyValues("tgg.merchant.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MerchantService.class);
        });
    }
}

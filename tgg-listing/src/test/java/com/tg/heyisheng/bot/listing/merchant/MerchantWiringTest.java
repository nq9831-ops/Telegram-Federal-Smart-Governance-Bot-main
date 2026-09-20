package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.listing.command.MerchantApplyCommandHandler;
import com.tg.heyisheng.bot.listing.command.MerchantDepositCommandHandler;
import com.tg.heyisheng.bot.listing.command.MerchantExitCommandHandler;
import com.tg.heyisheng.bot.listing.command.MerchantReviewCommandHandler;
import com.tg.heyisheng.bot.listing.command.MerchantSettleCommandHandler;
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
 * {@link RepositoryStub} 补替身——<b>关键是替身自身也受同一个开关门控</b>，
 * 否则「关闭方向」会被替身污染而失去意义（与 {@code ListingWiringTest} 同款纪律）。
 *
 * <p>四个命令处理器靠 {@code @BotCommand}（元注解 {@code @Component}）进组件扫描；
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

    @Test
    void disabledByDefaultProducesNoMerchantBeans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(MerchantProperties.class);
            assertThat(context).doesNotHaveBean(MerchantService.class);
            assertThat(context).doesNotHaveBean(MerchantReviewGuard.class);
            assertThat(context).doesNotHaveBean(MerchantDepositService.class);
            assertThat(context).doesNotHaveBean(DepositGateway.class);
            assertThat(context).doesNotHaveBean(MerchantApplyCommandHandler.class);
            assertThat(context).doesNotHaveBean(MerchantReviewCommandHandler.class);
            assertThat(context).doesNotHaveBean(MerchantStatusCommandHandler.class);
            assertThat(context).doesNotHaveBean(MerchantExitCommandHandler.class);
            assertThat(context).doesNotHaveBean(MerchantDepositCommandHandler.class);
            assertThat(context).doesNotHaveBean(MerchantSettleCommandHandler.class);
        });
    }

    @Test
    void explicitlyDisabledAlsoProducesNoMerchantBeans() {
        runner.withPropertyValues("tgg.merchant.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(MerchantProperties.class);
            assertThat(context).doesNotHaveBean(MerchantService.class);
            assertThat(context).doesNotHaveBean(MerchantReviewGuard.class);
            assertThat(context).doesNotHaveBean(MerchantDepositService.class);
            assertThat(context).doesNotHaveBean(DepositGateway.class);
            assertThat(context).doesNotHaveBean(MerchantApplyCommandHandler.class);
            assertThat(context).doesNotHaveBean(MerchantReviewCommandHandler.class);
            assertThat(context).doesNotHaveBean(MerchantStatusCommandHandler.class);
            assertThat(context).doesNotHaveBean(MerchantExitCommandHandler.class);
            assertThat(context).doesNotHaveBean(MerchantDepositCommandHandler.class);
            assertThat(context).doesNotHaveBean(MerchantSettleCommandHandler.class);
        });
    }

    @Test
    void enabledWiresServiceGuardDepositAndCommands() {
        runner.withPropertyValues("tgg.merchant.enabled=true", "tgg.merchant.reviewers=888001")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MerchantProperties.class);
                    assertThat(context).hasSingleBean(MerchantService.class);
                    assertThat(context).hasSingleBean(MerchantReviewGuard.class);
                    assertThat(context).hasSingleBean(DepositGateway.class);
                    assertThat(context).hasSingleBean(MerchantDepositService.class);
                    assertThat(context).hasSingleBean(MerchantApplyCommandHandler.class);
                    assertThat(context).hasSingleBean(MerchantReviewCommandHandler.class);
                    assertThat(context).hasSingleBean(MerchantStatusCommandHandler.class);
                    assertThat(context).hasSingleBean(MerchantExitCommandHandler.class);
                    assertThat(context).hasSingleBean(MerchantDepositCommandHandler.class);
                    assertThat(context).hasSingleBean(MerchantSettleCommandHandler.class);

                    assertThat(context.getBean(MerchantProperties.class).getInitialScore()).isEqualTo(500);
                    assertThat(context.getBean(MerchantReviewGuard.class).isReviewer(888001L))
                            .as("配置里的复核人应真的进入白名单")
                            .isTrue();
                    assertThat(context.getBean(DepositGateway.class))
                            .as("默认装配的是接入位（noop），真实链上实现由部署方覆盖")
                            .isInstanceOf(NoopDepositGateway.class);
                });
    }

    @Test
    void enablingMerchantWithoutCreditModuleStartsCleanly() {
        // 模块六与模块七是两个独立开关：只开商家不应因「没有 CreditService bean」而启动失败
        runner.withPropertyValues("tgg.merchant.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MerchantService.class);
            assertThat(context).hasSingleBean(MerchantDepositService.class);
        });
    }
}

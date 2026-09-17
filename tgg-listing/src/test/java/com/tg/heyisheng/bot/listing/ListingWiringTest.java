package com.tg.heyisheng.bot.listing;

import com.tg.heyisheng.bot.listing.command.ListingAddCommandHandler;
import com.tg.heyisheng.bot.listing.command.ListingAppealCommandHandler;
import com.tg.heyisheng.bot.listing.command.ListingListCommandHandler;
import com.tg.heyisheng.bot.listing.merchant.MerchantConfiguration;
import com.tg.heyisheng.bot.listing.merchant.MerchantProperties;
import com.tg.heyisheng.bot.listing.merchant.MerchantRepository;
import com.tg.heyisheng.bot.listing.notify.SubmitterNotifier;
import com.tg.heyisheng.bot.listing.verification.GroupLinkVerificationJob;
import com.tg.heyisheng.bot.listing.verification.GroupLinkVerifier;
import com.tg.heyisheng.bot.listing.verification.VerificationRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 模块五/六装配门测试：断言开关的<b>两个方向</b>。
 *
 * <p>只测「关闭 → 无 bean」或只测「开启 → 有 bean」都等于没测——前者的实现可以永远不装配，
 * 后者的实现可以永远装配。两侧都断言，门控才成立。
 *
 * <p>⚠️ {@code ApplicationContextRunner} 不加载 JPA，Spring Data 不会创建仓库代理。故用
 * {@link RepositoryStub} 补上两个仓库替身——<b>关键</b>是替身自身也受同一个开关门控
 * （{@code @ConditionalOnProperty}），否则「关闭方向」会被替身污染而失去意义。
 *
 * <p>Wave 2 起本模块有了受门控的真实 bean（探针 / 服务 / 定时任务），
 * 它们同样必须满足「关闭即不存在」——故逐一双向断言，而不是只断言仓库那一个。
 */
class ListingWiringTest {

    /** 受同一开关门控的仓库替身，用于让「关闭 → 无仓库 bean」这一断言成立。 */
    @Configuration
    @ConditionalOnProperty(prefix = "tgg.listing", name = "enabled", havingValue = "true")
    static class RepositoryStub {

        @Bean
        ListingGroupRepository listingGroupRepository() {
            return mock(ListingGroupRepository.class);
        }

        @Bean
        VerificationRecordRepository verificationRecordRepository() {
            return mock(VerificationRecordRepository.class);
        }

        @Bean
        ListingAppealRepository listingAppealRepository() {
            return mock(ListingAppealRepository.class);
        }
    }

    /** 另一个同样带 {@code @EnableScheduling} 的配置：生产里 failover / admission 就是这样。 */
    @Configuration
    @EnableScheduling
    static class AnotherSchedulingConfiguration {
    }

    private final ApplicationContextRunner listingRunner = new ApplicationContextRunner()
            .withUserConfiguration(ListingConfiguration.class, RepositoryStub.class,
                    // 命令处理器靠 @BotCommand（元注解 @Component）进入组件扫描；ApplicationContextRunner
                    // 不做扫描，故在此显式注册——它们自持 @ConditionalOnProperty，门的两个方向照样被断言。
                    ListingAddCommandHandler.class, ListingListCommandHandler.class,
                    ListingAppealCommandHandler.class);

    private final ApplicationContextRunner merchantRunner = new ApplicationContextRunner()
            .withUserConfiguration(MerchantConfiguration.class)
            // MerchantConfiguration 的 merchantService 需要一个仓库 bean；ApplicationContextRunner
            // 不加载 JPA，故手工补替身（与上面的 RepositoryStub 同款理由）。
            .withBean(MerchantRepository.class, () -> mock(MerchantRepository.class));

    @Test
    void listingDisabledByDefaultProducesNoListingBeans() {
        listingRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ListingProperties.class);
            assertThat(context).doesNotHaveBean(ListingGroupRepository.class);
            assertThat(context).doesNotHaveBean(VerificationRecordRepository.class);
            assertThat(context).doesNotHaveBean(GroupLinkVerifier.class);
            assertThat(context).doesNotHaveBean(ListingGroupService.class);
            assertThat(context).doesNotHaveBean(GroupLinkVerificationJob.class);
            assertThat(context).doesNotHaveBean(SubmitterNotifier.class);
            assertThat(context).doesNotHaveBean(ListingAddCommandHandler.class);
            assertThat(context).doesNotHaveBean(ListingListCommandHandler.class);
            assertThat(context).doesNotHaveBean(ListingAppealCommandHandler.class);
        });
    }

    @Test
    void listingExplicitlyDisabledAlsoProducesNoListingBeans() {
        listingRunner.withPropertyValues("tgg.listing.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ListingProperties.class);
            assertThat(context).doesNotHaveBean(ListingGroupRepository.class);
            assertThat(context).doesNotHaveBean(VerificationRecordRepository.class);
            assertThat(context).doesNotHaveBean(GroupLinkVerifier.class);
            assertThat(context).doesNotHaveBean(ListingGroupService.class);
            assertThat(context).doesNotHaveBean(GroupLinkVerificationJob.class);
            assertThat(context).doesNotHaveBean(SubmitterNotifier.class);
            assertThat(context).doesNotHaveBean(ListingAddCommandHandler.class);
            assertThat(context).doesNotHaveBean(ListingListCommandHandler.class);
            assertThat(context).doesNotHaveBean(ListingAppealCommandHandler.class);
        });
    }

    @Test
    void listingEnabledWiresStateMachineBeans() {
        listingRunner.withPropertyValues("tgg.listing.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(ListingProperties.class);
            assertThat(context).hasSingleBean(ListingGroupRepository.class);
            assertThat(context).hasSingleBean(VerificationRecordRepository.class);
            assertThat(context).hasSingleBean(GroupLinkVerifier.class);
            assertThat(context).hasSingleBean(ListingGroupService.class);
            assertThat(context).hasSingleBean(GroupLinkVerificationJob.class);
            assertThat(context).hasSingleBean(ListingAppealRepository.class);
            assertThat(context).hasSingleBean(SubmitterNotifier.class);
            assertThat(context).hasSingleBean(ListingAddCommandHandler.class);
            assertThat(context).hasSingleBean(ListingListCommandHandler.class);
            assertThat(context).hasSingleBean(ListingAppealCommandHandler.class);
            assertThat(context.getBean(ListingProperties.class).getFailThreshold()).isEqualTo(3);
        });
    }

    @Test
    void enablingListingAlongsideAnotherSchedulingConfigurationStartsCleanly() {
        // 生产里 tgg.failover / tgg.admission 的配置类也带 @EnableScheduling；
        // 本模块（Wave 2 起）再引入一个，必须仍能干净启动——否则「启用模块五」会让整个上下文起不来
        listingRunner.withUserConfiguration(AnotherSchedulingConfiguration.class)
                .withPropertyValues("tgg.listing.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GroupLinkVerificationJob.class);
                });
    }

    @Test
    void merchantDisabledByDefaultProducesNoMerchantBeans() {
        merchantRunner.run(context -> assertThat(context).doesNotHaveBean(MerchantProperties.class));
    }

    @Test
    void merchantEnabledWiresProperties() {
        merchantRunner.withPropertyValues("tgg.merchant.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(MerchantProperties.class);
            assertThat(context.getBean(MerchantProperties.class).getInitialScore()).isEqualTo(500);
        });
    }
}

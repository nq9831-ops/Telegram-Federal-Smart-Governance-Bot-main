package com.tg.heyisheng.bot.listing;

import com.tg.heyisheng.bot.listing.merchant.MerchantConfiguration;
import com.tg.heyisheng.bot.listing.merchant.MerchantProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 模块五/六装配门测试：断言开关的<b>两个方向</b>。
 *
 * <p>只测「关闭 → 无 bean」或只测「开启 → 有 bean」都等于没测——前者的实现可以永远不装配，
 * 后者的实现可以永远装配。两侧都断言，门控才成立。
 *
 * <p>⚠️ {@code ApplicationContextRunner} 不加载 JPA，Spring Data 不会创建仓库代理。故用一个
 * {@link RepositoryStub} 补上 {@link ListingGroupRepository} 替身——<b>关键</b>是该替身自身也受
 * 同一个开关门控（{@code @ConditionalOnProperty}），否则「关闭方向」会被替身污染而失去意义。
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
    }

    private final ApplicationContextRunner listingRunner = new ApplicationContextRunner()
            .withUserConfiguration(ListingConfiguration.class, RepositoryStub.class);

    private final ApplicationContextRunner merchantRunner = new ApplicationContextRunner()
            .withUserConfiguration(MerchantConfiguration.class);

    @Test
    void listingDisabledByDefaultProducesNoListingBeans() {
        listingRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ListingProperties.class);
            assertThat(context).doesNotHaveBean(ListingGroupRepository.class);
        });
    }

    @Test
    void listingExplicitlyDisabledAlsoProducesNoListingBeans() {
        listingRunner.withPropertyValues("tgg.listing.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ListingProperties.class);
            assertThat(context).doesNotHaveBean(ListingGroupRepository.class);
        });
    }

    @Test
    void listingEnabledWiresPropertiesAndRepository() {
        listingRunner.withPropertyValues("tgg.listing.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(ListingProperties.class);
            assertThat(context).hasSingleBean(ListingGroupRepository.class);
            assertThat(context.getBean(ListingProperties.class).getFailThreshold()).isEqualTo(3);
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

package com.tg.heyisheng.bot.escrow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 模块十二装配门测试：断言开关的<b>两个方向</b>（与 {@code MerchantWiringTest} 同纪律）。
 *
 * <p>只测「关闭 → 无 bean」或只测「开启 → 有 bean」都等于没测。两侧都断言，门控才成立。
 *
 * <p>⚠️ {@code ApplicationContextRunner} 不加载 JPA，Spring Data 不会创建仓库代理，故用
 * {@link RepositoryStub} 补替身——<b>替身自身也受同一个开关门控</b>，否则「关闭方向」会被替身污染。
 */
class EscrowWiringTest {

    /** 受同一开关门控的仓库替身，用于让「关闭 → 无仓库 bean」这一断言成立。 */
    @Configuration
    @ConditionalOnProperty(prefix = "tgg.escrow", name = "enabled", havingValue = "true")
    static class RepositoryStub {

        @Bean
        EscrowRepository escrowRepository() {
            return mock(EscrowRepository.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EscrowConfiguration.class, RepositoryStub.class);

    @Test
    void disabledByDefaultProducesNoEscrowBeans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(EscrowProperties.class);
            assertThat(context).doesNotHaveBean(EscrowService.class);
            assertThat(context).doesNotHaveBean(EscrowRepository.class);
        });
    }

    @Test
    void explicitlyDisabledAlsoProducesNoEscrowBeans() {
        runner.withPropertyValues("tgg.escrow.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(EscrowProperties.class);
            assertThat(context).doesNotHaveBean(EscrowService.class);
            assertThat(context).doesNotHaveBean(EscrowRepository.class);
        });
    }

    @Test
    void enabledWiresServiceAndProperties() {
        runner.withPropertyValues("tgg.escrow.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EscrowProperties.class);
            assertThat(context).hasSingleBean(EscrowService.class);
            assertThat(context).hasSingleBean(EscrowRepository.class);

            EscrowProperties properties = context.getBean(EscrowProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getDisputeWindowDays()).isEqualTo(7);
            assertThat(properties.getOrderTimeoutHours()).isEqualTo(72);
        });
    }
}

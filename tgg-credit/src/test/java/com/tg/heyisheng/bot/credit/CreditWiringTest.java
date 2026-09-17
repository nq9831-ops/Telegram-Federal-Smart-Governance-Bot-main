package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.exception.TggConfigException;
import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.credit.CreditEventSink;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 模块七装配测试：验证开关语义与 fail-fast。
 *
 * <p><b>为什么必须测装配</b>：契约的"未启用 = 无影响"与"启用缺 key = 启动失败"都是
 * <b>装配层</b>的行为，单测覆盖不到。本项目在模块九踩过"漏装配即静默失效"的坑，
 * 故装配行为要有独立断言。
 */
class CreditWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CreditConfiguration.class);

    @Test
    void disabledByDefaultProducesNoCreditBeans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(CreditService.class);
            assertThat(context).doesNotHaveBean(CreditEventSink.class);
        });
    }

    @Test
    void disabledExplicitlyAlsoProducesNoCreditBeans() {
        runner.withPropertyValues("tgg.credit.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(CreditService.class);
            assertThat(context).doesNotHaveBean(CreditEventSink.class);
        });
    }

    @Test
    void enabledWithoutSigningKeyFailsFast() {
        runner.withPropertyValues("tgg.credit.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .as("缺签名密钥必须让上下文启动失败，而非仅告警")
                    .hasRootCauseInstanceOf(TggConfigException.class);
        });
    }

    @Test
    void enabledWithKeyWiresSinkBean() {
        runner.withPropertyValues("tgg.credit.enabled=true", "tgg.credit.signing-key=test-key")
                .withBean(CreditScoreRepository.class, () -> mock(CreditScoreRepository.class))
                .withBean(IdHasher.class, IdHasher::fromEnvironment)
                .run(context -> {
                    assertThat(context).hasSingleBean(CreditEventSink.class);
                    assertThat(context).hasSingleBean(PenaltySigner.class);
                    assertThat(context).hasSingleBean(CreditService.class);
                });
    }
}

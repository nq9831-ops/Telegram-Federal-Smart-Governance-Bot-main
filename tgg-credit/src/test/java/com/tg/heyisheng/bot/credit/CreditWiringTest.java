package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.exception.TggConfigException;
import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.credit.CreditEventSink;
import com.tg.heyisheng.bot.core.wordfilter.TeachGate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 模块七装配测试：验证开关语义与 fail-fast。
 *
 * <p>⚠️ 2026-09-17：签名配置由 {@code tgg.credit.signing-key}（HMAC 共享串）改为
 * {@code tgg.credit.private-key}（Ed25519 PKCS#8 Base64 私钥）——本测试随之更新，
 * 且"启用"用例必须给**合法**私钥（非法会在 {@code @PostConstruct} 抛 {@link TggConfigException}）。
 */
class CreditWiringTest {

    private static final String VALID_PRIVATE_KEY = generatePrivateKeyBase64();

    private static String generatePrivateKeyBase64() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

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
    void enabledWithoutPrivateKeyFailsFast() {
        runner.withPropertyValues("tgg.credit.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .as("缺签名私钥必须让上下文启动失败，而非仅告警")
                    .hasRootCauseInstanceOf(TggConfigException.class);
        });
    }

    @Test
    void enabledWithValidKeyWiresSinkBean() {
        runner.withPropertyValues("tgg.credit.enabled=true", "tgg.credit.private-key=" + VALID_PRIVATE_KEY)
                .withBean(CreditScoreRepository.class, () -> mock(CreditScoreRepository.class))
                .withBean(CreditEventRecordRepository.class, () -> mock(CreditEventRecordRepository.class))
                .withBean(IdHasher.class, IdHasher::fromEnvironment)
                .run(context -> {
                    assertThat(context).hasSingleBean(CreditEventSink.class);
                    assertThat(context).hasSingleBean(PenaltySigner.class);
                    assertThat(context).hasSingleBean(CreditService.class);
                });
    }

    @Test
    void enabledRegistersTheTeachingGateSoCoreAggregatorCanPickItUp() {
        runner.withPropertyValues("tgg.credit.enabled=true", "tgg.credit.private-key=" + VALID_PRIVATE_KEY)
                .withBean(CreditScoreRepository.class, () -> mock(CreditScoreRepository.class))
                .withBean(CreditEventRecordRepository.class, () -> mock(CreditEventRecordRepository.class))
                .withBean(IdHasher.class, IdHasher::fromEnvironment)
                .run(context -> {
                    assertThat(context).hasSingleBean(TeachGate.class);
                    assertThat(context.getBean(TeachGate.class)).isInstanceOf(NoDeductionTeachGate.class);
                });
    }

    @Test
    void disabledProducesNoTeachingGate() {
        runner.run(context -> assertThat(context).doesNotHaveBean(TeachGate.class));
    }
}

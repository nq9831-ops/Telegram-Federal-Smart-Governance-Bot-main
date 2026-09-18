package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.config.TggCoreConfiguration;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.core.wordfilter.TeachEligibility;
import com.tg.heyisheng.bot.core.wordfilter.TeachGate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>跨模块接线</b>测试（模块九 §10.3）：模块七注册的门槛必须真的被 core 的聚合器收进来。
 *
 * <p><b>为什么不能只靠两端各自的单测</b>：core 的聚合器测过、credit 的 gate 也测过，
 * 但「Spring 会不会把 credit 的 {@code TeachGate} bean 注入到 core 聚合器的
 * {@code List<TeachGate>} 参数里」是<b>两端之间</b>的事——两端单测都绿，中间断链照样静默失效
 * （本项目在模块九反复踩过「绿了但链路没接上」）。故此处<b>同时</b>加载真实的
 * {@code CreditConfiguration} 与真实的 core 聚合方法，走一遍完整装配。
 *
 * <p><b>聚合器用的是真实实现而非复刻 stub</b>：{@link CoreAggregator} 内部持有的
 * {@link TggCoreConfiguration} 是真实装配类，调的是它真实的 {@code teachEligibility(List)} 方法
 * ——复刻一个 stub 会在 core 改实现时静默漂移，等于白测。
 */
class TeachGateCompositionWiringTest {

    private static final long CHAT = -100900999L;
    private static final long USER = 777L;
    private static final String VALID_PRIVATE_KEY = generatePrivateKeyBase64();

    private static String generatePrivateKeyBase64() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    /** 把真实的 core 聚合器接进测试上下文——不引入 core 的全部 bean（那需要几十个替身）。 */
    @Configuration
    static class CoreAggregator {

        private final TggCoreConfiguration core = new TggCoreConfiguration();

        @Bean
        TeachEligibility teachEligibility(List<TeachGate> gates) {
            return core.teachEligibility(gates);
        }
    }

    private static CreditScoreRepository repositoryReturning(int score) {
        CreditScoreRepository repository = mock(CreditScoreRepository.class);
        when(repository.findBySubjectTypeAndSubjectId(CreditSubjectType.INDIVIDUAL, USER))
                .thenReturn(Optional.of(new CreditScore(CreditSubjectType.INDIVIDUAL, USER, score, Instant.now())));
        return repository;
    }

    private ApplicationContextRunner runnerWith(CreditScoreRepository repository) {
        return new ApplicationContextRunner()
                .withUserConfiguration(CreditConfiguration.class, CoreAggregator.class)
                .withBean(CreditScoreRepository.class, () -> repository)
                .withBean(IdHasher.class, IdHasher::fromEnvironment);
    }

    @Test
    void deductedUserIsBlockedThroughTheRealComposition() {
        runnerWith(repositoryReturning(CreditService.INITIAL_SCORE - 1))
                .withPropertyValues("tgg.credit.enabled=true", "tgg.credit.private-key=" + VALID_PRIVATE_KEY)
                .run(context -> {
                    TeachEligibility eligibility = context.getBean(TeachEligibility.class);

                    assertThat(eligibility.rejectionFor(CHAT, USER))
                            .as("模块七的「无扣分」门槛必须经 core 聚合器生效")
                            .hasValueSatisfying(reason -> assertThat(reason).contains("信用良好"));
                });
    }

    @Test
    void cleanUserPassesThroughTheRealComposition() {
        runnerWith(repositoryReturning(CreditService.INITIAL_SCORE))
                .withPropertyValues("tgg.credit.enabled=true", "tgg.credit.private-key=" + VALID_PRIVATE_KEY)
                .run(context -> {
                    TeachEligibility eligibility = context.getBean(TeachEligibility.class);

                    assertThat(eligibility.rejectionFor(CHAT, USER)).isEmpty();
                });
    }

    @Test
    void disabledCreditModuleContributesNoGateAtAll() {
        runnerWith(repositoryReturning(CreditService.INITIAL_SCORE - 1))
                .run(context -> {
                    TeachEligibility eligibility = context.getBean(TeachEligibility.class);

                    assertThat(eligibility.rejectionFor(CHAT, USER))
                            .as("模块七未启用时，「无扣分」门槛不得存在——行为与升级前逐字一致")
                            .isEmpty();
                });
    }
}

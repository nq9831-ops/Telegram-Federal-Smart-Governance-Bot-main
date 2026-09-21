package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.credit.CreditEvent;
import com.tg.heyisheng.bot.core.credit.CreditEventType;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 信用分<b>反向补偿</b>（退分）的真库测试——模块十一的「推翻案件应退回已扣的分」。
 *
 * <p><b>为什么必须真库</b>：退分要读<b>原流水的实际变化量</b>（{@code score_before/score_after}），
 * 而该值受数据库侧 {@code applyDelta} 的 {@code GREATEST/LEAST} 夹取影响——mock 测不出这条。
 *
 * <p><b>为什么退分不能用规则重算</b>：见
 * {@link #reversalRestoresActualDeltaNotRuleRecomputedDelta}——分数 10 时硬红线 −100 实际只扣 10
 * （夹取到 0），若按规则算 −100 的相反数退 +100，会把 10 分的人退到 100，凭空多给 90 分。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CreditService.class)
class CreditReversalIT {

    private static final long USER_A = 910001L;

    /** 切片测试不扫 @Service 之外的装配；依赖在此手工提供（同 CreditPersistenceIT）。 */
    @TestConfiguration
    static class Deps {
        @Bean
        CreditRuleEngine creditRuleEngine() {
            return new BuiltInCreditRules();
        }

        @Bean
        IdHasher idHasher() {
            return IdHasher.fromEnvironment();
        }
    }

    @Autowired
    private CreditScoreRepository repository;

    @Autowired
    private CreditService service;

    @Autowired
    private CreditEventRecordRepository eventRecords;

    @BeforeEach
    void clear() {
        eventRecords.deleteAll();
        repository.deleteAll();
    }

    private static CreditEvent keyed(long userId, CreditEventType type, RiskLevel severity,
                                     boolean hardLine, String key) {
        return CreditEvent.of(CreditSubjectType.INDIVIDUAL, userId, type, severity, hardLine,
                "moderation", false, key);
    }

    /** 原扣分事件：幂等键形态与生产一致（{@code moderation:<chatId>:<messageId>}）。 */
    private static final String ORIGINAL_KEY = "moderation:-100900001:777";
    private static final String REVERSAL_KEY = "moderation-reversal:-100900001:777";

    @Test
    void reversalRestoresTheActuallyDeductedPoints() {
        service.apply(keyed(USER_A, CreditEventType.MODERATION_HIT, RiskLevel.HIGH, false, ORIGINAL_KEY));
        assertThat(service.scoreOf(CreditSubjectType.INDIVIDUAL, USER_A))
                .as("前置：HIGH 扣 30").isEqualTo(70);

        boolean reversed = service.reverseOf(ORIGINAL_KEY, REVERSAL_KEY, "case-rejected");

        assertThat(reversed).as("确实退了分").isTrue();
        assertThat(service.scoreOf(CreditSubjectType.INDIVIDUAL, USER_A))
                .as("推翻案件后应回到扣分前的分数").isEqualTo(100);
    }

    /**
     * <b>核心用例</b>：退分量必须取自流水的实际变化量，而非按规则重算。
     */
    @Test
    void reversalRestoresActualDeltaNotRuleRecomputedDelta() {
        // 先把分扣到 10（三次 HIGH：100 → 70 → 40 → 10）
        String k1 = "moderation:-100900001:1";
        String k2 = "moderation:-100900001:2";
        String k3 = "moderation:-100900001:3";
        service.apply(keyed(USER_A, CreditEventType.MODERATION_HIT, RiskLevel.HIGH, false, k1));
        service.apply(keyed(USER_A, CreditEventType.MODERATION_HIT, RiskLevel.HIGH, false, k2));
        service.apply(keyed(USER_A, CreditEventType.MODERATION_HIT, RiskLevel.HIGH, false, k3));
        assertThat(service.scoreOf(CreditSubjectType.INDIVIDUAL, USER_A)).isEqualTo(10);

        // 硬红线 −100：10 → 夹取到 0（**实际只扣了 10**）
        String hardKey = "moderation:-100900001:4";
        service.apply(keyed(USER_A, CreditEventType.MODERATION_HIT, RiskLevel.HIGH, true, hardKey));
        assertThat(service.scoreOf(CreditSubjectType.INDIVIDUAL, USER_A))
                .as("触底夹取到 0").isEqualTo(0);

        service.reverseOf(hardKey, "moderation-reversal:-100900001:4", "case-rejected");

        assertThat(service.scoreOf(CreditSubjectType.INDIVIDUAL, USER_A))
                .as("只退实际扣掉的 10 分（回到 10），绝不可按规则重算退 100")
                .isEqualTo(10);
    }

    @Test
    void reversalIsIdempotentOnItsOwnKey() {
        service.apply(keyed(USER_A, CreditEventType.MODERATION_HIT, RiskLevel.HIGH, false, ORIGINAL_KEY));

        assertThat(service.reverseOf(ORIGINAL_KEY, REVERSAL_KEY, "case-rejected")).isTrue();
        boolean second = service.reverseOf(ORIGINAL_KEY, REVERSAL_KEY, "case-rejected");

        assertThat(second).as("同键第二次不再退分").isFalse();
        assertThat(service.scoreOf(CreditSubjectType.INDIVIDUAL, USER_A))
                .as("重复退分不得把分数推高").isEqualTo(100);
    }

    @Test
    void reversalWithoutOriginalEventDoesNothing() {
        boolean reversed = service.reverseOf("moderation:-1:999", "moderation-reversal:-1:999", "case-rejected");

        assertThat(reversed).as("无原事件可退").isFalse();
        assertThat(service.scoreOf(CreditSubjectType.INDIVIDUAL, USER_A))
                .as("不得凭空改分").isEqualTo(100);
    }

    @Test
    void reversalWithNullKeysIsRejected() {
        assertThat(service.reverseOf(null, REVERSAL_KEY, "x")).isFalse();
        assertThat(service.reverseOf(ORIGINAL_KEY, null, "x")).isFalse();
    }

    /** 退分写的是**独立**的补偿流水（原记录不可改不可删），且类型可辨识。 */
    @Test
    void reversalIsRecordedAsASeparateReversalFlow() {
        service.apply(keyed(USER_A, CreditEventType.MODERATION_HIT, RiskLevel.HIGH, false, ORIGINAL_KEY));
        service.reverseOf(ORIGINAL_KEY, REVERSAL_KEY, "case-rejected");

        assertThat(eventRecords.findByIdempotencyKey(ORIGINAL_KEY))
                .as("原记录仍在（只追加）").isPresent();
        assertThat(eventRecords.findByIdempotencyKey(REVERSAL_KEY))
                .as("补偿流水已独立落库")
                .isPresent()
                .get()
                .satisfies(record -> {
                    assertThat(record.getEventType()).isEqualTo(CreditEventType.MODERATION_REVERSAL);
                    assertThat(record.getScoreDelta()).as("补偿为正向").isEqualTo(30);
                });
    }
}

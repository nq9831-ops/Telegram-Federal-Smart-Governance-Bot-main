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
 * 信用分账本的真库持久化测试 —— 直连本机 MySQL（与 {@code BannedWordPersistenceIT} 同取舍）。
 *
 * <p>覆盖 mock 测不到的部分：Flyway V4 迁移是否真的建了表、native upsert 语句在真实 MySQL 上的行为、
 * {@code GREATEST} 下界夹取是否生效、唯一约束是否让重复记账落在同一行。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CreditService.class)
class CreditPersistenceIT {

    private static final long USER_A = 900001L;
    private static final long USER_B = 900002L;

    /** @DataJpaTest 是切片测试，不扫描 @Service 之外的装配；依赖在此手工提供。 */
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

    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    private static CreditEvent hit(long userId, RiskLevel severity, boolean hardLine) {
        return CreditEvent.of(CreditSubjectType.INDIVIDUAL, userId,
                CreditEventType.MODERATION_HIT, severity, hardLine, "test");
    }

    @Test
    void firstEventCreatesLedgerRowWithInitialMinusDelta() {
        CreditOutcome outcome = service.apply(hit(USER_A, RiskLevel.HIGH, false));

        assertThat(outcome.score()).as("100 起始分扣 HIGH(-30)").isEqualTo(70);
        assertThat(service.scoreOf(CreditSubjectType.INDIVIDUAL, USER_A)).isEqualTo(70);
    }

    @Test
    void repeatedEventsUpdateTheSameRowInsteadOfInsertingNew() {
        service.apply(hit(USER_A, RiskLevel.MEDIUM, false));  // -15
        service.apply(hit(USER_A, RiskLevel.MEDIUM, false));  // -15

        assertThat(repository.findAll()).as("同一主体只应有一行账本").hasSize(1);
        assertThat(service.scoreOf(CreditSubjectType.INDIVIDUAL, USER_A)).isEqualTo(70);
    }

    @Test
    void scoreNeverGoesBelowZero() {
        // 连续硬红线（每条 -100）：GREATEST(0, ...) 必须把分值夹在 0，不得为负
        for (int i = 0; i < 5; i++) {
            service.apply(hit(USER_A, RiskLevel.HIGH, true));
        }

        assertThat(service.scoreOf(CreditSubjectType.INDIVIDUAL, USER_A)).isZero();
    }

    @Test
    void differentSubjectsHaveIsolatedScores() {
        service.apply(hit(USER_A, RiskLevel.HIGH, false));

        assertThat(service.scoreOf(CreditSubjectType.INDIVIDUAL, USER_B))
                .as("另一主体的分值不受影响")
                .isEqualTo(CreditService.INITIAL_SCORE);
    }

    @Test
    void thresholdCrossingYieldsPenalty() {
        // 100 - 30 - 30 - 30 = 10 → 低于 MUTE_BELOW(30) → MUTE
        service.apply(hit(USER_A, RiskLevel.HIGH, false));
        service.apply(hit(USER_A, RiskLevel.HIGH, false));
        CreditOutcome outcome = service.apply(hit(USER_A, RiskLevel.HIGH, false));

        assertThat(outcome.score()).isEqualTo(10);
        assertThat(outcome.penalty()).isEqualTo(PenaltyType.MUTE);
        assertThat(outcome.triggeredPenalty()).isTrue();
    }
}

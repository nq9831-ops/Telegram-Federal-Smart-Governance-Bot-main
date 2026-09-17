package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.core.credit.CreditEvent;
import com.tg.heyisheng.bot.core.credit.CreditEventType;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置信用规则的单测。
 *
 * <p>断言的是<b>分值增量</b>这一真实产出（不是"方法没抛异常"）。
 */
class BuiltInCreditRulesTest {

    private final CreditRuleEngine engine = new BuiltInCreditRules();

    private static CreditEvent event(RiskLevel severity, boolean hardLine) {
        return CreditEvent.of(CreditSubjectType.INDIVIDUAL, 42L,
                CreditEventType.MODERATION_HIT, severity, hardLine, "test");
    }

    @Test
    void hardLineTouchesBottomEvenWhenSeverityClaimsLow() {
        // 硬红线优先于等级比较——低等级不得掩盖硬红线的"立即触底"
        assertThat(engine.deltaFor(event(RiskLevel.LOW, true)))
                .isEqualTo(BuiltInCreditRules.HARD_LINE_DELTA);
        assertThat(engine.deltaFor(event(RiskLevel.NONE, true)))
                .isEqualTo(BuiltInCreditRules.HARD_LINE_DELTA);
    }

    @Test
    void highSeverityDeductsThirty() {
        assertThat(engine.deltaFor(event(RiskLevel.HIGH, false)))
                .isEqualTo(BuiltInCreditRules.HIGH_DELTA);
    }

    @Test
    void mediumSeverityDeductsFifteen() {
        assertThat(engine.deltaFor(event(RiskLevel.MEDIUM, false)))
                .isEqualTo(BuiltInCreditRules.MEDIUM_DELTA);
    }

    @Test
    void lowSeverityDeductsFive() {
        assertThat(engine.deltaFor(event(RiskLevel.LOW, false)))
                .isEqualTo(BuiltInCreditRules.LOW_DELTA);
    }

    @Test
    void cleanSeverityDeductsNothing() {
        assertThat(engine.deltaFor(event(RiskLevel.NONE, false)))
                .isEqualTo(BuiltInCreditRules.NONE_DELTA);
    }

    @Test
    void nullEventIsTreatedAsNoChange() {
        assertThat(engine.deltaFor(null)).isEqualTo(BuiltInCreditRules.NONE_DELTA);
    }
}

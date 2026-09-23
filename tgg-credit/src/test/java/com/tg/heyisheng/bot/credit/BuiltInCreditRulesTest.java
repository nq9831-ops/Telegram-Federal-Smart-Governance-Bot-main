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

    /**
     * 模块十二：担保交易完成 → <b>加分</b>。
     *
     * <p>这条用例存在的理由：既有规则只按 severity 产出 {@code ≤ 0} 的增量（见类头常量），
     * 交易完成需要的正增量在旧路径上<b>不可达</b>——不新开类型表就永远加不上去，
     * 而"加了事件类型但分数没动"正是本项目最怕的静默失效。
     */
    @Test
    void escrowCompletedAddsPoints() {
        CreditEvent completed = CreditEvent.of(CreditSubjectType.INDIVIDUAL, 42L,
                CreditEventType.ESCROW_COMPLETED, RiskLevel.NONE, false, "escrow");
        assertThat(engine.deltaFor(completed))
                .isEqualTo(BuiltInCreditRules.ESCROW_COMPLETED_DELTA)
                .isPositive();
    }

    /** 模块十二：担保交易欺诈 → 一扣到底，且<b>不依赖</b>上报的 severity（与硬红线同款纪律）。 */
    @Test
    void escrowFraudTouchesBottomRegardlessOfSeverity() {
        CreditEvent fraudLow = CreditEvent.of(CreditSubjectType.INDIVIDUAL, 42L,
                CreditEventType.ESCROW_FRAUD, RiskLevel.LOW, false, "escrow");
        assertThat(engine.deltaFor(fraudLow))
                .isEqualTo(BuiltInCreditRules.ESCROW_FRAUD_DELTA);
    }

    /** 类型表只对登记过的 escrow 类型生效：既有 6 个类型仍走 severity 路径（零回归）。 */
    @Test
    void nonEscrowTypesStillFollowSeverityPath() {
        assertThat(engine.deltaFor(event(RiskLevel.HIGH, false)))
                .isEqualTo(BuiltInCreditRules.HIGH_DELTA);
        assertThat(engine.deltaFor(event(RiskLevel.NONE, false)))
                .isEqualTo(BuiltInCreditRules.NONE_DELTA);
    }
}

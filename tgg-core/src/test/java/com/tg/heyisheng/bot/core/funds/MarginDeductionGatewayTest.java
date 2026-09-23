package com.tg.heyisheng.bot.core.funds;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarginDeductionGateway} 默认实现的契约守卫。
 *
 * <p>它守的是一条容易写反的性质：接缝未接通时<b>必须显式拒绝</b>，而不是返回成功。
 * 若 noop 假装成功，担保交易的罚金兜底链会认为"已从保证金扣到钱"，从而<b>跳过</b>
 * 罚金账本与联邦基金两级——资金缺口被静默掩盖。
 */
class MarginDeductionGatewayTest {

    @Test
    void noopRejectsExplicitlyInsteadOfPretendingSuccess() {
        MarginDeductionGateway gateway = MarginDeductionGateway.noop();

        MarginDeductionGateway.DeductionOutcome outcome =
                gateway.deduct(42L, new BigDecimal("100.00000000"), "仲裁费兜底");

        assertThat(outcome.deducted())
                .as("未接通的接缝不得假装扣款成功——否则兜底链会跳过下一级")
                .isFalse();
        assertThat(outcome.deductedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(outcome.detail())
                .as("拒绝必须带可读原因（运维/审计要能看出是哪种未接通）")
                .isNotBlank();
    }

    @Test
    void okCarriesTheActuallyDeductedAmount() {
        MarginDeductionGateway.DeductionOutcome ok =
                MarginDeductionGateway.DeductionOutcome.ok(new BigDecimal("37.5"));

        assertThat(ok.deducted()).isTrue();
        assertThat(ok.deductedAmount()).isEqualByComparingTo(new BigDecimal("37.5"));
    }
}

package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「信用良好（无扣分）」教学门槛（模块九 §10.3）——原文「≥400」重映射后的边界。
 *
 * <p>守的不变量：<b>恰好等于初始分即放行</b>（判据是 {@code >=}，不是 {@code >}）。
 * 这一格的边界最易写错，而写错的方向决定它是「误伤所有正常用户」还是「门槛形同虚设」。
 */
class NoDeductionTeachGateTest {

    private static final long CHAT = -100900999L;
    private static final long USER = 777L;

    private final CreditService creditService = mock(CreditService.class);
    private final NoDeductionTeachGate gate = new NoDeductionTeachGate(creditService);

    private void scoreIs(int score) {
        when(creditService.scoreOf(CreditSubjectType.INDIVIDUAL, USER)).thenReturn(score);
    }

    @Test
    void exactlyAtInitialScoreAllows() {
        scoreIs(CreditService.INITIAL_SCORE);

        assertThat(gate.rejectionFor(CHAT, USER)).isEmpty();
    }

    @Test
    void aboveInitialScoreAllows() {
        scoreIs(CreditService.INITIAL_SCORE + 10);

        assertThat(gate.rejectionFor(CHAT, USER)).isEmpty();
    }

    @Test
    void onePointBelowInitialScoreRejects() {
        scoreIs(CreditService.INITIAL_SCORE - 1);

        assertThat(gate.rejectionFor(CHAT, USER))
                .hasValueSatisfying(reason -> assertThat(reason).contains("信用良好"));
    }

    @Test
    void bottomScoreRejects() {
        scoreIs(CreditService.MIN_SCORE);

        assertThat(gate.rejectionFor(CHAT, USER)).isPresent();
    }

    @Test
    void unknownUserIsRejectedWithoutQueryingTheLedger() {
        assertThat(gate.rejectionFor(CHAT, null)).isPresent();

        verifyNoInteractions(creditService);
    }
}

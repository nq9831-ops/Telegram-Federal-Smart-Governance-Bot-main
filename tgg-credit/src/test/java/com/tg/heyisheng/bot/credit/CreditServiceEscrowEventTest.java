package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.credit.CreditEvent;
import com.tg.heyisheng.bot.core.credit.CreditEventType;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模块十二：担保交易事件到达信用账本的<b>接线</b>证据。
 *
 * <p><b>为什么必须用真实规则引擎</b>：{@code BuiltInCreditRulesTest} 只证明 {@code deltaFor}
 * 的返回值；本类用真实 {@link BuiltInCreditRules} 驱动 {@link CreditService#apply}，证明
 * 「事件类型 → 增量 → <b>账本实际收到该增量</b>」这条链是通的。
 *
 * <p><b>它守的是什么</b>：新增 {@code CreditEventType} 却漏登记 {@code TYPE_DELTAS} 时，
 * 增量会从 severity 路径拿到 0——事件"发了"、日志"正常"、守门测试"全绿"、<b>分数纹丝不动</b>。
 * 本类的 {@code capturedDelta} 断言是这条静默失效的 RED 复现点。
 */
class CreditServiceEscrowEventTest {

    /** 真实规则引擎（刻意不是 mock——mock 会让本类退化为"再测一遍自己的假设"）。 */
    private final CreditRuleEngine rules = new BuiltInCreditRules();
    private final CreditScoreRepository repository = mock(CreditScoreRepository.class);
    private final CreditEventRecordRepository eventRecords = mock(CreditEventRecordRepository.class);
    private final IdHasher hasher = mock(IdHasher.class);
    private final CreditService service = new CreditService(rules, repository, eventRecords, hasher);

    /** 账本：锁读给 before、回读给 after（模拟库侧已应用增量）。 */
    private void ledgerGoesFromTo(int before, int after) {
        CreditScore locked = mock(CreditScore.class);
        when(locked.getScore()).thenReturn(before);
        when(repository.findForUpdate(any(), anyLong())).thenReturn(Optional.of(locked));

        CreditScore applied = mock(CreditScore.class);
        when(applied.getScore()).thenReturn(after);
        when(repository.findBySubjectTypeAndSubjectId(any(), anyLong())).thenReturn(Optional.of(applied));

        // 流水「首次」：Mockito 对 int 方法默认返回 0，会被读成「重复事件」而不改分
        when(eventRecords.insertIfAbsent(any(), anyLong(), any(), any(), anyBoolean(),
                anyInt(), anyInt(), anyInt(), any(), any(), any(), any())).thenReturn(1);
    }

    private int capturedDelta() {
        ArgumentCaptor<Integer> delta = ArgumentCaptor.forClass(Integer.class);
        verify(repository).applyDelta(anyString(), anyLong(), delta.capture(),
                anyInt(), anyInt(), any(Instant.class));
        return delta.getValue();
    }

    @Test
    void escrowCompletedAppliesPositiveDeltaToLedger() {
        ledgerGoesFromTo(100, 110);

        service.apply(CreditEvent.of(CreditSubjectType.INDIVIDUAL, 42L,
                CreditEventType.ESCROW_COMPLETED, RiskLevel.NONE, false, "escrow"));

        assertThat(capturedDelta())
                .as("交易完成必须把正增量真正交给账本；只加枚举、漏改类型表时这里会是 0")
                .isEqualTo(BuiltInCreditRules.ESCROW_COMPLETED_DELTA);
    }

    @Test
    void escrowFraudAppliesBottomOutDeltaEvenWhenSeverityClaimsLow() {
        ledgerGoesFromTo(100, 0);

        service.apply(CreditEvent.of(CreditSubjectType.INDIVIDUAL, 42L,
                CreditEventType.ESCROW_FRAUD, RiskLevel.LOW, false, "escrow"));

        assertThat(capturedDelta())
                .as("欺诈一扣到底，且不因上报 LOW 而少扣")
                .isEqualTo(BuiltInCreditRules.ESCROW_FRAUD_DELTA);
    }
}

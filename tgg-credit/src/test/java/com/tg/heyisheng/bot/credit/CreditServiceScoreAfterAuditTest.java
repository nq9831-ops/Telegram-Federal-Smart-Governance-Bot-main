package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.credit.CreditEvent;
import com.tg.heyisheng.bot.core.credit.CreditEventType;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流水 {@code score_after} 必须等于<b>记账后的账本实际分</b>——审计不变量的守门。
 *
 * <p><b>缺陷背景</b>（CODE-REVIEW-2026-09-21 #4 结案）：写流水时 {@code score_after} 是预测值，
 * 修复前的预测公式恒为 {@code clamp(before + delta)}。而写库语义是「{@code delta == 0} 时<b>不写库</b>、
 * 分值原样保留」——两者只在「分值越界 + delta == 0」时分叉，且该场景<b>确定性可达</b>：
 * 商家初值 500（{@code ensureInitialized} 刻意不夹取，见其 javadoc 与 KNOWN-ISSUES #206）
 * 高于 {@code MAX_SCORE}(150)，任何 {@code delta == 0} 的事件（规则引擎契约：未知输入返回 0）
 * 都会让预测值记 150、实际仍是 500 ⇒ <b>流水审计断裂</b>。
 *
 * <p><b>为什么修复在预测公式而不是回填</b>：回填 {@code updateScoreAfter} 按
 * {@code WHERE idempotency_key = :key} 寻址——无幂等键的流水行<b>不可寻址</b>
 * （{@code key = NULL} 时 SQL 恒不匹配，且 {@link CreditEventRecordRepository#insertIfAbsent}
 * 的契约刻意是「null 键恒插入、不去重」，行里就是 NULL）。让预测值与写库语义逐字对齐，
 * 才是把错误状态拦在<b>产生处</b>；回填补留为有键事件的第二道防线。
 */
class CreditServiceScoreAfterAuditTest {

    private final CreditRuleEngine rules = mock(CreditRuleEngine.class);
    private final CreditScoreRepository repository = mock(CreditScoreRepository.class);
    private final CreditEventRecordRepository eventRecords = mock(CreditEventRecordRepository.class);
    private final IdHasher hasher = mock(IdHasher.class);
    private final CreditService service = new CreditService(rules, repository, eventRecords, hasher);

    /** 账本行存在且锁读到 {@code score}；流水写入视为「首次」（Mockito int 默认 0 会被读成重复事件）。 */
    private void ledgerIs(int score) {
        CreditScore row = mock(CreditScore.class);
        when(row.getScore()).thenReturn(score);
        when(repository.findBySubjectTypeAndSubjectId(any(), anyLong())).thenReturn(Optional.of(row));
        when(repository.findForUpdate(any(), anyLong())).thenReturn(Optional.of(row));
        when(eventRecords.insertIfAbsent(any(), anyLong(), any(), any(), anyBoolean(),
                anyInt(), anyInt(), anyInt(), any(), any(), any(), any())).thenReturn(1);
    }

    /** 取本次写入流水的 {@code score_after} 预测值。 */
    private int recordedScoreAfter() {
        ArgumentCaptor<Integer> scoreAfter = ArgumentCaptor.forClass(Integer.class);
        verify(eventRecords).insertIfAbsent(any(), anyLong(), any(), any(), anyBoolean(),
                anyInt(), anyInt(), scoreAfter.capture(), any(), any(), any(), any());
        return scoreAfter.getValue();
    }

    @Test
    void zeroDeltaOnOutOfRangeLedgerRecordsActualScoreNotClampedPrediction() {
        // 商家初值 500（越界，ensureInitialized 刻意不夹取）+ delta == 0（规则引擎对未知输入的契约返回值）
        when(rules.deltaFor(any())).thenReturn(0);
        ledgerIs(500);

        service.apply(CreditEvent.of(
                CreditSubjectType.MERCHANT, 42L, CreditEventType.MODERATION_HIT,
                RiskLevel.LOW, false, "test")); // 便捷工厂 → 幂等键为 null（契约：不参与去重）

        assertThat(recordedScoreAfter())
                .as("delta == 0 时账本分原样保留（500），流水不得记夹取预测值（150）——审计必须等于实际")
                .isEqualTo(500);
    }

    @Test
    void keyedBackfillStillCorrectsWhenActualDriftsFromPrediction() {
        // 第二道防线：有键事件在「预测 ≠ 实际」时仍须回填（此处用 mock 强制漂移，测的是回填机制本身）
        when(rules.deltaFor(any())).thenReturn(-5);
        ledgerIs(13);
        CreditScore drifted = mock(CreditScore.class);
        when(drifted.getScore()).thenReturn(7); // 与预测 clamp(13-5)=8 不同 —— 强制漂移
        when(repository.findBySubjectTypeAndSubjectId(any(), anyLong())).thenReturn(Optional.of(drifted));

        service.apply(CreditEvent.of(
                CreditSubjectType.INDIVIDUAL, 42L, CreditEventType.MODERATION_HIT,
                RiskLevel.LOW, false, "test", false, "moderation:1:2"));

        verify(eventRecords).updateScoreAfter("moderation:1:2", 7);
    }

    @Test
    void floorClampStillRecordedWhenDeltaDrivesBelowZero() {
        // 回归护栏：预测公式改动不得破坏「触底」夹取（10 − 100 → 记 0，与 applyDelta 的 GREATEST/LEAST 同口径）
        when(rules.deltaFor(any())).thenReturn(-100);
        ledgerIs(10);
        CreditScore floored = mock(CreditScore.class);
        when(floored.getScore()).thenReturn(0);
        when(repository.findBySubjectTypeAndSubjectId(any(), anyLong())).thenReturn(Optional.of(floored));

        service.apply(CreditEvent.of(
                CreditSubjectType.INDIVIDUAL, 42L, CreditEventType.MODERATION_HIT,
                RiskLevel.HIGH, true, "test", false, "moderation:1:3"));

        assertThat(recordedScoreAfter())
                .as("触底夹取口径不变：预测与实际都应是 0")
                .isEqualTo(0);
        verify(eventRecords, never()).updateScoreAfter(any(), anyInt());
    }
}

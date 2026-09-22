package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.credit.CreditEventType;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.junit.jupiter.api.Test;

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
 * 退分链套娃守门（议事会 F3 残项）：{@code reverseOf} 不得对一条<b>补偿流水</b>再补偿。
 *
 * <p><b>缺陷背景</b>：补偿流水（MODERATION_REVERSAL）只追加、分值已回升 ⇒ 其
 * {@code score_after > score_before} ⇒ {@code actualDelta > 0} ⇒ {@code refund = -actualDelta}
 * 为负——「退分」被翻转成<b>再扣一次</b>。修复在类型检查处拒绝（与 apply 拒绝
 * MODERATION_REVERSAL 对称），本测试钉死该守卫。
 */
class CreditServiceReversalGuardTest {

    private final CreditScoreRepository repository = mock(CreditScoreRepository.class);
    private final CreditEventRecordRepository eventRecords = mock(CreditEventRecordRepository.class);
    private final IdHasher hasher = mock(IdHasher.class);
    private final CreditService service = new CreditService(
            event -> 0, repository, eventRecords, hasher);

    @Test
    void reversingAReversalIsRefundedNoMore() {
        // 原「流水」本身是一条补偿：分值回升 100 → 150（actualDelta=+50），旧代码会 refund=-50 再扣一次
        CreditEventRecord reversalRow = mock(CreditEventRecord.class);
        when(reversalRow.getEventType()).thenReturn(CreditEventType.MODERATION_REVERSAL);
        when(reversalRow.getSubjectType()).thenReturn(CreditSubjectType.INDIVIDUAL);
        when(reversalRow.getSubjectId()).thenReturn(42L);
        when(reversalRow.getScoreBefore()).thenReturn(100);
        when(reversalRow.getScoreAfter()).thenReturn(150);
        when(reversalRow.getSeverity()).thenReturn(RiskLevel.HIGH);
        when(reversalRow.isHardLine()).thenReturn(false);
        when(eventRecords.findByIdempotencyKey("moderation:1:2")).thenReturn(Optional.of(reversalRow));

        boolean refunded = service.reverseOf("moderation:1:2", "moderation-reversal:1:2", "guard-test");

        assertThat(refunded)
                .as("补偿流水不可再退——否则退分翻转成再扣（退分链套娃）")
                .isFalse();
        verify(eventRecords, never()).insertIfAbsent(any(), anyLong(), any(), any(), anyBoolean(),
                anyInt(), anyInt(), anyInt(), any(), any(), any(), any());
        verify(repository, never()).applyDelta(any(), anyLong(), anyInt(), anyInt(), anyInt(), any());
    }
}

package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.credit.CreditEvent;
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
import static org.mockito.Mockito.when;

/**
 * 模块七对「<b>显式联邦上报</b>」的响应（模块九 §10.5「三次联邦标记」的落地通道）。
 *
 * <p><b>为什么必须有这条显式通道</b>：原文的扣分是 5/15/30，起始 100 → 三次之后仍是 50，
 * 按分数阈值（≤0 → {@code REPORT_TO_FEDERATION}）**永远触发不了上报**。
 * 若只依赖分数副作用，「三次联邦标记」就是一句空话——本类即该结论的护栏。
 */
class CreditServiceFederationReportTest {

    private final CreditRuleEngine rules = mock(CreditRuleEngine.class);
    private final CreditScoreRepository repository = mock(CreditScoreRepository.class);
    private final CreditEventRecordRepository eventRecords = mock(CreditEventRecordRepository.class);
    private final IdHasher hasher = mock(IdHasher.class);
    private final CreditService service = new CreditService(rules, repository, eventRecords, hasher);

    private void scoreIs(int score) {
        CreditScore row = mock(CreditScore.class);
        when(row.getScore()).thenReturn(score);
        when(repository.findBySubjectTypeAndSubjectId(any(), anyLong())).thenReturn(Optional.of(row));
        // 流水写入「首次」：Mockito 对 int 方法默认返回 0，会被读成「重复事件」而不扣分
        // ——那不是本类要测的语义，必须显式 stub 成 1。
        when(eventRecords.insertIfAbsent(any(), anyLong(), any(), any(), anyBoolean(),
                anyInt(), anyInt(), anyInt(), any(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void explicitFederationReportWinsOverHighScore() {
        when(rules.deltaFor(any())).thenReturn(-30);
        scoreIs(50); // 远未到 ≤0 的触发线

        CreditOutcome outcome = service.apply(CreditEvent.of(
                CreditSubjectType.INDIVIDUAL, 42L, CreditEventType.MODERATION_HIT,
                RiskLevel.HIGH, false, "test", true));

        assertThat(outcome.penalty())
                .as("显式上报必须生效，不受分数阈值约束")
                .isEqualTo(PenaltyType.REPORT_TO_FEDERATION);
    }

    @Test
    void withoutExplicitFlagHighScoreTriggersNothing() {
        when(rules.deltaFor(any())).thenReturn(-30);
        scoreIs(70); // 高于 WARN 阈值（60）——「未到任何线」

        CreditOutcome outcome = service.apply(CreditEvent.of(
                CreditSubjectType.INDIVIDUAL, 42L, CreditEventType.MODERATION_HIT,
                RiskLevel.HIGH, false, "test"));

        assertThat(outcome.penalty())
                .as("分数未到线且未显式要求 → 不触发（证明上一条不是分数副作用）")
                .isEqualTo(PenaltyType.NONE);
    }
}

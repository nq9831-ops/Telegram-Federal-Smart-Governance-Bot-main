package com.tg.heyisheng.bot.core.retention;

import com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository;
import com.tg.heyisheng.bot.core.moderation.ReviewStatus;
import com.tg.heyisheng.bot.core.moderation.SensitiveTopicStrikeRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 保留策略测试（模块十 §11.2）。
 *
 * <p><b>两条最要紧的护栏</b>：① 审计表<b>永不清理</b>（否则上一波立的「不可删」就地自我否定）；
 * ② 默认<b>只报告不清理</b>——保留策略的本质是自动删数据，默认就删是危险的。
 */
class RetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

    private final ModerationReviewRepository reviews = mock(ModerationReviewRepository.class);
    private final SensitiveTopicStrikeRepository strikes = mock(SensitiveTopicStrikeRepository.class);
    private final RetentionProperties properties = new RetentionProperties();

    private RetentionService service() {
        return new RetentionService(reviews, strikes, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void reportMarksAuditLogAsNeverPurgeable() {
        List<RetentionService.Finding> findings = service().report();

        RetentionService.Finding audit = findings.stream()
                .filter(finding -> RetentionService.AUDIT_TABLE.equals(finding.table()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("报告里必须显式列出审计表，说明它为何不清理"));

        assertThat(audit.purgeable()).as("审计表必须被硬编码排除，且不是配置项").isFalse();
        assertThat(audit.note()).contains("不可删");
    }

    @Test
    void reportCountsExpiredRowsPerRule() {
        when(reviews.countByStatusAndCreatedAtBefore(eq(ReviewStatus.APPROVED), any())).thenReturn(3L);
        when(reviews.countByStatusAndCreatedAtBefore(eq(ReviewStatus.REJECTED), any())).thenReturn(1L);
        when(strikes.countByLastAtBefore(any())).thenReturn(7L);

        List<RetentionService.Finding> findings = service().report();

        assertThat(findings).extracting(RetentionService.Finding::expired).contains(3L, 1L, 7L);
    }

    @Test
    void purgeDoesNothingWhenDisabledAndReportsMinusOne() {
        long result = service().purge();

        assertThat(result).as("-1 = 未启用，与「启用但无可删」（0）区分开").isEqualTo(-1);
        verify(reviews, never()).deleteByStatusAndCreatedAtBefore(any(), any());
        verify(strikes, never()).deleteByLastAtBefore(any());
    }

    @Test
    void purgeDeletesWhenExplicitlyEnabled() {
        properties.setEnabled(true);
        when(reviews.deleteByStatusAndCreatedAtBefore(eq(ReviewStatus.APPROVED), any())).thenReturn(2L);
        when(reviews.deleteByStatusAndCreatedAtBefore(eq(ReviewStatus.REJECTED), any())).thenReturn(1L);
        when(strikes.deleteByLastAtBefore(any())).thenReturn(5L);

        assertThat(service().purge()).as("2 + 1 + 5").isEqualTo(8L);
    }
}

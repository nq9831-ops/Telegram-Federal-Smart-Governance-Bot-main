package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.core.notify.Notification;
import com.tg.heyisheng.bot.core.notify.NotificationDispatcher;
import com.tg.heyisheng.bot.core.notify.NotificationLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 红线复核 SLA 测试（模块九 §10.6「联邦快速复核：2 小时内人工确认」）。
 *
 * <p><b>为什么是「给现有队列加 SLA」而不是新造复核</b>：§10.4 的推翻权已经建成
 * 「命中即入队（含硬红线）→ 操作员裁决 → 推翻则解封」这条链；§10.6 缺的只是
 * <b>时间约束</b>（2 小时）。再造一套复核会产生两套并行的裁决入口——本项目明确警惕这种分裂。
 *
 * <p><b>只催办、不自动动作</b>：原文对「2 小时内未确认会怎样」<b>未作规定</b>。
 * 自动解封会让红线失效，自动升级又无定义，故此处只升级提醒、由人决定。
 */
class RedLineReviewSlaTest {

    private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");

    private final ModerationReviewRepository repository = mock(ModerationReviewRepository.class);
    private final ModerationReviewGuard guard = new ModerationReviewGuard("900,901");
    private final NotificationDispatcher notifications = mock(NotificationDispatcher.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private RedLineReviewSla sla() {
        return new RedLineReviewSla(repository, guard, notifications, clock,
                Duration.ofHours(2));
    }

    @Test
    void alarmsWhenHardLinePendingBeyondSla() {
        when(repository.findByHardLineTrueAndStatusAndCreatedAtBefore(
                ReviewStatus.PENDING, NOW.minus(Duration.ofHours(2))))
                .thenReturn(List.of(new ModerationReviewItem(-100900999L, 777L, 5,
                        List.of("HARD_CSAM"), RiskLevel.HIGH, true)));

        sla().alarm();

        verify(notifications, org.mockito.Mockito.times(2)).notify(any(Notification.class));
        verify(notifications, org.mockito.Mockito.times(2)).notify(org.mockito.ArgumentMatchers.argThat(
                n -> n.level() == NotificationLevel.URGENT));
    }

    @Test
    void silentWhenNothingBeyondSla() {
        when(repository.findByHardLineTrueAndStatusAndCreatedAtBefore(
                ReviewStatus.PENDING, NOW.minus(Duration.ofHours(2))))
                .thenReturn(List.of());

        sla().alarm();

        assertThat(guard.size()).isEqualTo(2);
        verify(notifications, never()).notify(any(Notification.class));
    }

    /** 查询条件必须锁死「硬红线 + 仍待裁决」——把普通条目也算进来会稀释催办。 */
    @Test
    void queriesOnlyHardLinePendingBeforeCutoff() {
        when(repository.findByHardLineTrueAndStatusAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of());

        sla().alarm();

        verify(repository).findByHardLineTrueAndStatusAndCreatedAtBefore(
                ReviewStatus.PENDING, NOW.minus(Duration.ofHours(2)));
    }
}

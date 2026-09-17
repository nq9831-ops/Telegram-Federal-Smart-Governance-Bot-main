package com.tg.heyisheng.bot.core.notify;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 免打扰与延迟发送测试（模块十 §11.1）。
 *
 * <p>守三件事：<b>非紧急暂存</b>（不丢弃）、<b>紧急不受阻</b>（封禁/解封必须立刻到）、
 * <b>冲刷逐用户判定</b>（不能按全局时刻一刀切，否则先醒的人被后睡的人拖住）。
 */
class QuietHoursDispatcherTest {

    private static final long USER = 777L;
    private static final long OTHER = 778L;
    private static final Instant NOW = Instant.parse("2026-09-21T03:00:00Z");

    private final NotificationPreferenceService preferences = mock(NotificationPreferenceService.class);
    private final DeferredNotificationRepository deferred = mock(DeferredNotificationRepository.class);
    private final List<String> sent = new ArrayList<>();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private NotificationDispatcher dispatcher() {
        return new NotificationDispatcher((id, text) -> sent.add(text),
                new NotificationRateLimiter(clock), preferences, deferred, clock);
    }

    @Test
    void nonUrgentIsDeferredDuringQuietHoursInsteadOfBeingDropped() {
        when(preferences.isQuietAt(eq(USER), any())).thenReturn(true);

        boolean delivered = dispatcher().notify(
                new Notification(NotificationLevel.IMPORTANT, USER, "信用分变动"));

        assertThat(delivered).isFalse();
        assertThat(sent).as("静默时段内不投递").isEmpty();
        verify(deferred).save(any(DeferredNotification.class));
    }

    @Test
    void urgentBypassesQuietHours() {
        when(preferences.isQuietAt(eq(USER), any())).thenReturn(true);

        boolean delivered = dispatcher().notify(
                new Notification(NotificationLevel.URGENT, USER, "你已被解封"));

        assertThat(delivered).as("紧急通知必须立刻送达——否则误封要多持续一夜").isTrue();
        assertThat(sent).containsExactly("你已被解封");
        verify(deferred, never()).save(any());
    }

    @Test
    void outsideQuietHoursSendsNormally() {
        when(preferences.isQuietAt(anyLong(), any())).thenReturn(false);

        assertThat(dispatcher().notify(new Notification(NotificationLevel.NORMAL, USER, "系统公告"))).isTrue();
        assertThat(sent).hasSize(1);
        verify(deferred, never()).save(any());
    }

    @Test
    void flusherSendsForWokenUsersAndKeepsStillQuietOnes() {
        DeferredNotification awake = new DeferredNotification(USER, NotificationLevel.IMPORTANT, "给已醒的人", NOW);
        DeferredNotification asleep = new DeferredNotification(OTHER, NotificationLevel.IMPORTANT, "给还睡着的人", NOW);
        when(deferred.findAllByOrderByIdAsc()).thenReturn(List.of(awake, asleep));
        when(preferences.isQuietAt(eq(USER), any())).thenReturn(false);
        when(preferences.isQuietAt(eq(OTHER), any())).thenReturn(true);

        int flushed = new DeferredNotificationFlusher(deferred, preferences, dispatcher(), clock).flushDue();

        assertThat(flushed).isEqualTo(1);
        assertThat(sent).containsExactly("给已醒的人");
        verify(deferred).delete(awake);
        verify(deferred, never()).delete(asleep);
    }
}

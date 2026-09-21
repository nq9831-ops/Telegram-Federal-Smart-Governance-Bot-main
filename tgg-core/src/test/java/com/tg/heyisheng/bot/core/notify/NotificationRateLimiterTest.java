package com.tg.heyisheng.bot.core.notify;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知频率门（模块十 §11.1）。
 *
 * <p>守的重点：<b>陈旧（收件人 × 级别）条目必须被驱逐</b>——{@link NotificationRateLimiter.Window}
 * 的计数是惰性滚动的，若不清理，每个只出现过一次的收件人都会永久留下一条，长跑（用户维度）内存无界增长。
 * 与 {@code InMemoryRateLimiter} 同类问题、同款摊销清理范式。
 */
class NotificationRateLimiterTest {

    /** 可手动推进的时钟（与 InMemoryRateLimiterTest 同款）。 */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration delta) {
            now.updateAndGet(c -> c.plus(delta));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @Test
    void purgesIdleRecipientsAfterBothWindowsRoll() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        NotificationRateLimiter limiter = new NotificationRateLimiter(clock);

        limiter.decide(NotificationLevel.NORMAL, 1);
        limiter.decide(NotificationLevel.NORMAL, 2);
        assertThat(limiter.trackedWindows()).isEqualTo(2);

        clock.advance(Duration.ofHours(25));   // 跨过小时窗与天窗

        assertThat(limiter.purgeStale()).as("两个条目都已陈旧，应全部移除").isEqualTo(2);
        assertThat(limiter.trackedWindows()).as("长跑后条目集合不应无界保留").isZero();
    }

    @Test
    void keepsEntriesWhoseDayWindowIsStillActive() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        NotificationRateLimiter limiter = new NotificationRateLimiter(clock);

        limiter.decide(NotificationLevel.NORMAL, 1);
        clock.advance(Duration.ofMinutes(90));   // 小时窗滚过，但仍在同一天

        assertThat(limiter.purgeStale())
                .as("天窗未滚：删除会重置当日配额，故必须保留").isZero();
        assertThat(limiter.trackedWindows()).isEqualTo(1);
    }

    @Test
    void keepsEntryWithPendingSuppressedCount() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        NotificationRateLimiter limiter = new NotificationRateLimiter(clock);

        // NORMAL 每小时 1 条：第 2 条被抑制 → suppressed=1（待下次额度可用时合并发送）
        limiter.decide(NotificationLevel.NORMAL, 7);
        limiter.decide(NotificationLevel.NORMAL, 7);

        clock.advance(Duration.ofHours(25));

        assertThat(limiter.purgeStale())
                .as("有被抑制待合并的条数，删除会丢计数").isZero();
    }

    /** 摊销清理：无需人工调用 purgeStale，decide 自身按 PURGE_INTERVAL 触发。 */
    @Test
    void decideAmortizesEvictionOfIdleRecipients() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        NotificationRateLimiter limiter = new NotificationRateLimiter(clock);

        // 4 轮 × 500 = 2000 次 decide；PURGE_INTERVAL=1024 → 至少触发一次自动清理
        for (int round = 0; round < 4; round++) {
            for (long r = 0; r < 500; r++) {
                limiter.decide(NotificationLevel.NORMAL, round * 1000L + r);
            }
            clock.advance(Duration.ofHours(25));   // 每轮结束跨过天窗
        }

        assertThat(limiter.trackedWindows())
                .as("若自动清理生效，最老旧几轮的条目应已被移除（否则累计 2000）")
                .isLessThan(1500);
    }

    @Test
    void urgentLevelIsUnlimitedAndNotTracked() {
        NotificationRateLimiter limiter = new NotificationRateLimiter(Clock.systemUTC());

        assertThat(limiter.decide(NotificationLevel.URGENT, 1).allowed()).isTrue();
        assertThat(limiter.trackedWindows()).as("不受限级别不建条目").isZero();
    }
}

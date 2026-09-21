package com.tg.heyisheng.bot.admin.identity;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录限流（按来源 IP 的滑动窗口）。
 *
 * <p>守三点：达上限即拒、不同来源互不影响、窗口滑过后配额恢复。
 */
class AdminLoginRateLimiterTest {

    /** 可推进的时钟——用于验证窗口滑动（固定时钟测不了时间前进）。 */
    static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
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
            return now;
        }
    }

    @Test
    void allowsUpToLimitThenBlocks() {
        AdminLoginRateLimiter limiter = new AdminLoginRateLimiter(
                3, Duration.ofMinutes(1), new MutableClock(Instant.parse("2026-09-22T00:00:00Z")));

        assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4")).as("超限即拒").isFalse();
    }

    @Test
    void differentKeysAreIndependent() {
        AdminLoginRateLimiter limiter = new AdminLoginRateLimiter(
                1, Duration.ofMinutes(1), new MutableClock(Instant.parse("2026-09-22T00:00:00Z")));

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("b")).as("不同来源互不影响").isTrue();
    }

    @Test
    void windowSlidesAndRestoresQuota() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-22T00:00:00Z"));
        AdminLoginRateLimiter limiter = new AdminLoginRateLimiter(1, Duration.ofMinutes(1), clock);

        assertThat(limiter.tryAcquire("ip")).isTrue();
        assertThat(limiter.tryAcquire("ip")).isFalse();

        clock.advance(Duration.ofMinutes(2));   // 窗口滑过

        assertThat(limiter.tryAcquire("ip")).as("窗口过后配额恢复").isTrue();
    }
}

package com.tg.heyisheng.bot.core.ratelimit;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.middleware.MiddlewareChain;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 滑动窗口限流测试。用可手动推进的时钟验证「窗口过期恢复」——否则只能靠真实 sleep，既慢又不稳。
 */
class InMemoryRateLimiterTest {

    private static final Duration WINDOW = Duration.ofSeconds(10);

    /** 可手动推进的时钟。 */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration delta) {
            now.updateAndGet(current -> current.plus(delta));
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
    void allowsUpToLimitWithinWindow() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(3, WINDOW, new MutableClock(Instant.EPOCH));

        assertThat(limiter.tryAcquire("u:1")).isTrue();
        assertThat(limiter.tryAcquire("u:1")).isTrue();
        assertThat(limiter.tryAcquire("u:1")).isTrue();
        assertThat(limiter.tryAcquire("u:1")).as("第 4 次应被拒").isFalse();
    }

    @Test
    void recoversAfterWindowExpires() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(2, WINDOW, clock);

        assertThat(limiter.tryAcquire("u:1")).isTrue();
        assertThat(limiter.tryAcquire("u:1")).isTrue();
        assertThat(limiter.tryAcquire("u:1")).isFalse();

        clock.advance(WINDOW.plusSeconds(1));

        assertThat(limiter.tryAcquire("u:1")).as("窗口过期后应恢复").isTrue();
    }

    @Test
    void keysAreIndependent() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(1, WINDOW, new MutableClock(Instant.EPOCH));

        assertThat(limiter.tryAcquire("u:1")).isTrue();
        assertThat(limiter.tryAcquire("u:2")).as("不同维度键互不影响").isTrue();
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> new InMemoryRateLimiter(0, WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void middlewareBlocksWhenUserQuotaExhausted() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        RateLimitMiddleware middleware = new RateLimitMiddleware(
                new InMemoryRateLimiter(1, WINDOW, clock),
                new InMemoryRateLimiter(100, WINDOW, clock),
                new InMemoryRateLimiter(100, WINDOW, clock));
        UpdateContext ctx = new UpdateContext(1, 42L, -100L, "/echo");
        MiddlewareChain chain = new MiddlewareChain(List.of());

        assertThat(middleware.handle(ctx, chain)).isTrue();
        assertThat(middleware.handle(ctx, chain)).as("用户配额耗尽后应中断").isFalse();
    }
}

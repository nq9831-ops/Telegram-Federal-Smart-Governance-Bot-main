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

    /**
     * 陈旧键驱逐：窗口内已无活动的键必须被移除——否则"只出现一次的用户"会永久留下键，
     * 长跑下 map 无界增长（内存泄漏）。
     */
    @Test
    void purgeStaleEvictsKeysWithoutRecentActivity() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(5, WINDOW, clock);

        assertThat(limiter.tryAcquire("u:1")).isTrue();
        assertThat(limiter.tryAcquire("u:2")).isTrue();
        assertThat(limiter.trackedKeys()).isEqualTo(2);

        clock.advance(WINDOW.plusSeconds(1));

        assertThat(limiter.purgeStale()).as("两个键都已过期，应全部移除").isEqualTo(2);
        assertThat(limiter.trackedKeys()).as("长跑后键集合不应无界保留").isZero();
    }

    /** 驱逐不得误伤仍在窗口内活跃的键。 */
    @Test
    void purgeStaleKeepsActiveKeys() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(5, WINDOW, clock);

        limiter.tryAcquire("u:1");
        clock.advance(WINDOW.dividedBy(2));
        limiter.tryAcquire("u:2");

        assertThat(limiter.purgeStale()).as("两个键都还在窗口内").isZero();
        assertThat(limiter.trackedKeys()).isEqualTo(2);
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

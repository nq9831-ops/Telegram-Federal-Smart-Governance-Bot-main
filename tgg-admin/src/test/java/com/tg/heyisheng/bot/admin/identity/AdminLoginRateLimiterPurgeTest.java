package com.tg.heyisheng.bot.admin.identity;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 陈旧键淘汰守门（议事会 GUARD-3）：只失败过一次便不再出现的来源 IP 不得永久占用键位。
 *
 * <p>原缺陷：{@code hits} map 只增不删，IP 轮换下 auth 路径内存无界增长。
 * 修复比照 {@code InMemoryRateLimiter.purgeStale}：按调用次数摊销触发淘汰。
 * （内存治理类断言——旧实现连 {@code trackedKeys} 都没有，红在「API 不存在」层。）
 */
class AdminLoginRateLimiterPurgeTest {

    /** 可推进时钟：固定起点 T0，测试中手动前进越过窗口。 */
    private static final class SteppingClock extends Clock {
        private final AtomicReference<Instant> now;

        SteppingClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration d) {
            now.updateAndGet(t -> t.plus(d));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Test
    void staleKeysArePurgedAfterWindowExpires() {
        SteppingClock clock = new SteppingClock(Instant.parse("2026-09-22T00:00:00Z"));
        AdminLoginRateLimiter limiter = new AdminLoginRateLimiter(20, Duration.ofMinutes(1), clock);

        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("stale-ip-" + i);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(5);

        // 全部键陈旧之后继续活动，按调用次数摊销触发淘汰
        clock.advance(Duration.ofMinutes(2));
        for (int i = 0; i < 1100; i++) {
            limiter.tryAcquire("fresh-ip");
        }

        assertThat(limiter.trackedKeys())
                .as("过期来源 IP 的键应被释放，map 不得无界增长")
                .isLessThan(5);
    }
}

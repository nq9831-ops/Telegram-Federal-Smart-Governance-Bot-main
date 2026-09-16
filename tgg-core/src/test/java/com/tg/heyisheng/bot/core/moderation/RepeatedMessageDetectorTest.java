package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.core.ratelimit.InMemoryRateLimiter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反刷屏检测器测试。
 *
 * <p>核心是**不误伤**：只有"同一用户 + 同一群 + 相同内容 + 超出阈值"才判刷屏；
 * 换用户、换内容、换群、或还在阈值内，都必须放行。
 */
class RepeatedMessageDetectorTest {

    private static final long CHAT = -100L;
    private static final long USER = 42L;
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final int ALLOWED = 3;

    /** 可手动推进的时钟——否则验证"窗口过期恢复"只能靠 sleep，慢且不稳。 */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);

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

    private static RepeatedMessageDetector detector(MutableClock clock) {
        return new RepeatedMessageDetector(new InMemoryRateLimiter(ALLOWED, WINDOW, clock));
    }

    @Test
    void allowsRepeatsUpToThreshold() {
        RepeatedMessageDetector d = detector(new MutableClock());

        for (int i = 0; i < ALLOWED; i++) {
            assertThat(d.inspect(CHAT, USER, "收到")).as("阈值内第 %d 次应放行", i + 1).isEmpty();
        }
    }

    @Test
    void flagsRepeatsBeyondThreshold() {
        RepeatedMessageDetector d = detector(new MutableClock());

        for (int i = 0; i < ALLOWED; i++) {
            d.inspect(CHAT, USER, "快来刷单");
        }

        assertThat(d.inspect(CHAT, USER, "快来刷单"))
                .as("超出阈值即为重复刷屏")
                .isPresent()
                .get()
                .satisfies(v -> {
                    assertThat(v.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
                    assertThat(v.hardLine()).as("刷屏不是硬红线，应入复核而非冻结").isFalse();
                    assertThat(v.matchedRuleIds()).containsExactly("FLOOD_REPEAT");
                });
    }

    /** 不同用户各自计数——不能被别人的刷屏牵连。 */
    @Test
    void usersAreCountedIndependently() {
        RepeatedMessageDetector d = detector(new MutableClock());

        for (int i = 0; i < ALLOWED; i++) {
            d.inspect(CHAT, USER, "同一条");
        }

        assertThat(d.inspect(CHAT, 999L, "同一条"))
                .as("另一用户首次发相同内容不应被判刷屏").isEmpty();
    }

    @Test
    void differentContentIsNotFlood() {
        RepeatedMessageDetector d = detector(new MutableClock());

        for (int i = 0; i < ALLOWED + 2; i++) {
            assertThat(d.inspect(CHAT, USER, "第 " + i + " 条不同内容")).as("不同内容不算重复").isEmpty();
        }
    }

    /** 同一个群内不同群互不影响——按群隔离。 */
    @Test
    void chatsAreCountedIndependently() {
        RepeatedMessageDetector d = detector(new MutableClock());

        for (int i = 0; i < ALLOWED; i++) {
            d.inspect(CHAT, USER, "同一条");
        }

        assertThat(d.inspect(-200L, USER, "同一条")).as("换群应重新计数").isEmpty();
    }

    @Test
    void recoversAfterWindowExpires() {
        MutableClock clock = new MutableClock();
        RepeatedMessageDetector d = detector(clock);

        for (int i = 0; i < ALLOWED; i++) {
            d.inspect(CHAT, USER, "同一条");
        }
        assertThat(d.inspect(CHAT, USER, "同一条")).as("此时应判刷屏").isPresent();

        clock.advance(WINDOW.plusSeconds(1));

        assertThat(d.inspect(CHAT, USER, "同一条")).as("窗口过期后应重新放行").isEmpty();
    }

    @Test
    void ignoresMissingIdentityOrEmptyContent() {
        RepeatedMessageDetector d = detector(new MutableClock());

        assertThat(d.inspect(null, USER, "x")).isEmpty();
        assertThat(d.inspect(CHAT, null, "x")).as("无法归因到用户时不应判刷屏").isEmpty();
        assertThat(d.inspect(CHAT, USER, null)).isEmpty();
        assertThat(d.inspect(CHAT, USER, "   ")).isEmpty();
    }
}

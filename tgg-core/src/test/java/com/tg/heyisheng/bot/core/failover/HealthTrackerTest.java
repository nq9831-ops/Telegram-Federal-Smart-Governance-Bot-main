package com.tg.heyisheng.bot.core.failover;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HealthTrackerTest {

    @Test
    void triggersOnlyAfterConsecutiveFailuresReachThreshold() {
        HealthTracker tracker = new HealthTracker(3);

        assertThat(tracker.record(WebhookHealth.UNHEALTHY)).isFalse();
        assertThat(tracker.record(WebhookHealth.UNHEALTHY)).isFalse();
        assertThat(tracker.record(WebhookHealth.UNHEALTHY)).as("第 3 次连续失败才达阈值").isTrue();
    }

    @Test
    void successResetsConsecutiveCount() {
        HealthTracker tracker = new HealthTracker(3);

        tracker.record(WebhookHealth.UNHEALTHY);
        tracker.record(WebhookHealth.UNHEALTHY);
        tracker.record(WebhookHealth.HEALTHY);
        assertThat(tracker.consecutiveFailures()).as("成功后计数归零").isZero();

        assertThat(tracker.record(WebhookHealth.UNHEALTHY)).isFalse();
        assertThat(tracker.record(WebhookHealth.UNHEALTHY)).isFalse();
        assertThat(tracker.record(WebhookHealth.UNHEALTHY)).as("归零后需重新累计").isTrue();
    }

    /**
     * 核心不变量：UNKNOWN（探测通道故障，无可信结论）<b>不推进也不清零</b>降级计数。
     * 少了这条，一次网络抖动就会被当成 webhook 失效，触发不可逆的降级。
     */
    @Test
    void unknownDoesNotAdvanceNorResetTheCount() {
        HealthTracker tracker = new HealthTracker(3);

        tracker.record(WebhookHealth.UNHEALTHY);
        tracker.record(WebhookHealth.UNHEALTHY);

        assertThat(tracker.record(WebhookHealth.UNKNOWN)).as("UNKNOWN 本身不触发降级").isFalse();
        assertThat(tracker.consecutiveFailures()).as("UNKNOWN 不改变计数").isEqualTo(2);

        assertThat(tracker.record(WebhookHealth.UNHEALTHY))
                .as("已有两次明确失败，再来一次明确失败即达阈值——UNKNOWN 不应把进度清零")
                .isTrue();
    }

    @Test
    void rejectsNonPositiveThreshold() {
        assertThatThrownBy(() -> new HealthTracker(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

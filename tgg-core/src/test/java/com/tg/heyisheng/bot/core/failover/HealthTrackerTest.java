package com.tg.heyisheng.bot.core.failover;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HealthTrackerTest {

    @Test
    void triggersOnlyAfterConsecutiveFailuresReachThreshold() {
        HealthTracker tracker = new HealthTracker(3);

        assertThat(tracker.record(false)).isFalse();
        assertThat(tracker.record(false)).isFalse();
        assertThat(tracker.record(false)).as("第 3 次连续失败才达阈值").isTrue();
    }

    @Test
    void successResetsConsecutiveCount() {
        HealthTracker tracker = new HealthTracker(3);

        tracker.record(false);
        tracker.record(false);
        tracker.record(true);
        assertThat(tracker.consecutiveFailures()).as("成功后计数归零").isZero();

        assertThat(tracker.record(false)).isFalse();
        assertThat(tracker.record(false)).isFalse();
        assertThat(tracker.record(false)).as("归零后需重新累计").isTrue();
    }

    @Test
    void rejectsNonPositiveThreshold() {
        assertThatThrownBy(() -> new HealthTracker(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.tg.heyisheng.bot.core.failover;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PollingFallbackCoordinatorTest {

    /** 记录调用顺序的假控制器——真实启停需要 token 与网络，故用假实现验证决策逻辑。 */
    static class RecordingController implements TelegramModeController {
        final List<String> calls = new ArrayList<>();

        @Override
        public void stopWebhook() {
            calls.add("stopWebhook");
        }

        @Override
        public void startLongPolling() {
            calls.add("startLongPolling");
        }
    }

    @Test
    void doesNotDegradeBeforeThreshold() {
        RecordingController controller = new RecordingController();
        PollingFallbackCoordinator coordinator =
                new PollingFallbackCoordinator(new HealthTracker(3), () -> false, controller);

        assertThat(coordinator.probeOnce()).isFalse();
        assertThat(coordinator.probeOnce()).isFalse();

        assertThat(controller.calls).as("未达阈值不应有任何切换动作").isEmpty();
        assertThat(coordinator.isDegraded()).isFalse();
    }

    @Test
    void degradesAfterThresholdInCorrectOrder() {
        RecordingController controller = new RecordingController();
        PollingFallbackCoordinator coordinator =
                new PollingFallbackCoordinator(new HealthTracker(3), () -> false, controller);

        coordinator.probeOnce();
        coordinator.probeOnce();
        assertThat(coordinator.probeOnce()).as("第 3 次连续不健康应触发降级").isTrue();

        assertThat(controller.calls)
                .as("必须先停 webhook 再起长轮询，否则两种模式并存会重复处理同一 update")
                .containsExactly("stopWebhook", "startLongPolling");
        assertThat(coordinator.isDegraded()).isTrue();
    }

    @Test
    void healthyProbeNeverDegrades() {
        RecordingController controller = new RecordingController();
        PollingFallbackCoordinator coordinator =
                new PollingFallbackCoordinator(new HealthTracker(2), () -> true, controller);

        for (int i = 0; i < 5; i++) {
            assertThat(coordinator.probeOnce()).isFalse();
        }

        assertThat(controller.calls).isEmpty();
    }

    @Test
    void degradationIsIdempotent() {
        RecordingController controller = new RecordingController();
        PollingFallbackCoordinator coordinator =
                new PollingFallbackCoordinator(new HealthTracker(1), () -> false, controller);

        assertThat(coordinator.probeOnce()).isTrue();
        assertThat(coordinator.probeOnce()).as("已降级后不再重复切换").isFalse();
        assertThat(coordinator.degrade()).as("显式再触发也应无操作").isFalse();

        assertThat(controller.calls)
                .as("无论触发多少次，启停动作只应发生一轮")
                .containsExactly("stopWebhook", "startLongPolling");
    }
}

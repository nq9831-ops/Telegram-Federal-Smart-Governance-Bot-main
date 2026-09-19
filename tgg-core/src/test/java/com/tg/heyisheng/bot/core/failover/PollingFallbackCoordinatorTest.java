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
                new PollingFallbackCoordinator(new HealthTracker(3), () -> WebhookHealth.UNHEALTHY, controller);

        assertThat(coordinator.probeOnce()).isFalse();
        assertThat(coordinator.probeOnce()).isFalse();

        assertThat(controller.calls).as("未达阈值不应有任何切换动作").isEmpty();
        assertThat(coordinator.isDegraded()).isFalse();
    }

    @Test
    void degradesAfterThresholdInCorrectOrder() {
        RecordingController controller = new RecordingController();
        PollingFallbackCoordinator coordinator =
                new PollingFallbackCoordinator(new HealthTracker(3), () -> WebhookHealth.UNHEALTHY, controller);

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
                new PollingFallbackCoordinator(new HealthTracker(2), () -> WebhookHealth.HEALTHY, controller);

        for (int i = 0; i < 5; i++) {
            assertThat(coordinator.probeOnce()).isFalse();
        }

        assertThat(controller.calls).isEmpty();
    }

    /**
     * 核心不变量：探测通道故障（UNKNOWN）永远不触发降级——无论探测多少次。
     * 对比修复前：二态下探测异常一律判「不健康」，连续几次即降级到长轮询，
     * 而长轮询同样走不通那条网络，降级既无用又不可逆。
     */
    @Test
    void unknownProbeNeverDegrades() {
        RecordingController controller = new RecordingController();
        PollingFallbackCoordinator coordinator =
                new PollingFallbackCoordinator(new HealthTracker(2), () -> WebhookHealth.UNKNOWN, controller);

        for (int i = 0; i < 10; i++) {
            assertThat(coordinator.probeOnce()).isFalse();
        }

        assertThat(controller.calls).as("无可信结论不应触发任何切换").isEmpty();
        assertThat(coordinator.isDegraded()).isFalse();
    }

    /**
     * UNKNOWN 既不推进也不清零：它只是「没有信息」，不抹掉已确认的失败。
     * 故序列 U/UNK/U/UNK/U 中，累计到第 3 次明确失败时仍应降级——
     * Telegram 已经三次明确告知 webhook 不健康，中间的探测通道抖动不该让证据作废。
     */
    @Test
    void unknownDoesNotEraseConfirmedFailures() {
        RecordingController controller = new RecordingController();
        WebhookHealth[] script = {
                WebhookHealth.UNHEALTHY, WebhookHealth.UNKNOWN,
                WebhookHealth.UNHEALTHY, WebhookHealth.UNKNOWN,
                WebhookHealth.UNHEALTHY
        };
        int[] cursor = {0};
        WebhookHealthProbe probe = () -> script[cursor[0]++];

        PollingFallbackCoordinator coordinator =
                new PollingFallbackCoordinator(new HealthTracker(3), probe, controller);

        assertThat(coordinator.probeOnce()).as("第 1 次 UNHEALTHY").isFalse();
        assertThat(coordinator.probeOnce()).as("UNKNOWN 不改变计数").isFalse();
        assertThat(coordinator.probeOnce()).as("第 2 次 UNHEALTHY").isFalse();
        assertThat(coordinator.probeOnce()).as("UNKNOWN 不改变计数").isFalse();
        assertThat(coordinator.probeOnce()).as("第 3 次明确失败 → 降级").isTrue();

        assertThat(controller.calls).containsExactly("stopWebhook", "startLongPolling");
    }

    @Test
    void degradationIsIdempotent() {
        RecordingController controller = new RecordingController();
        PollingFallbackCoordinator coordinator =
                new PollingFallbackCoordinator(new HealthTracker(1), () -> WebhookHealth.UNHEALTHY, controller);

        assertThat(coordinator.probeOnce()).isTrue();
        assertThat(coordinator.probeOnce()).as("已降级后不再重复切换").isFalse();
        assertThat(coordinator.degrade()).as("显式再触发也应无操作").isFalse();

        assertThat(controller.calls)
                .as("无论触发多少次，启停动作只应发生一轮")
                .containsExactly("stopWebhook", "startLongPolling");
    }
}

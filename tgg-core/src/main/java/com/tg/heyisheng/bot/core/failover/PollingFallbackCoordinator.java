package com.tg.heyisheng.bot.core.failover;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 降级协调器：webhook 连续不健康达阈值时，把接收模式切换到长轮询。
 *
 * <p><b>幂等</b>：降级只发生一次——重复触发不会再调一遍启停动作
 * （否则会造成长轮询被反复重启）。
 *
 * <p><b>切换顺序</b>：先停 webhook、再起长轮询。反过来的话，
 * 会有一段时间两种模式同时在跑，同一条 update 可能被处理两次。
 */
public class PollingFallbackCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PollingFallbackCoordinator.class);

    private final HealthTracker healthTracker;
    private final WebhookHealthProbe probe;
    private final TelegramModeController modeController;
    private final AtomicBoolean degraded = new AtomicBoolean(false);

    public PollingFallbackCoordinator(HealthTracker healthTracker,
                                      WebhookHealthProbe probe,
                                      TelegramModeController modeController) {
        this.healthTracker = healthTracker;
        this.probe = probe;
        this.modeController = modeController;
    }

    /**
     * 执行一次健康探测，必要时触发降级。
     *
     * <p>只有 {@link WebhookHealth#UNHEALTHY}（Telegram 明确答复 webhook 不健康）才推进降级计数；
     * {@link WebhookHealth#UNKNOWN}（探测通道故障，无可信结论）不推进也不清零——
     * 详见 {@link HealthTracker#record(WebhookHealth)}。
     *
     * @return {@code true} 表示本次调用触发了降级
     */
    public boolean probeOnce() {
        WebhookHealth health = probe.probe();
        if (healthTracker.record(health)) {
            return degrade();
        }
        return false;
    }

    /**
     * 触发降级（幂等）。
     *
     * @return {@code true} 表示本次调用确实执行了切换
     */
    public boolean degrade() {
        if (!degraded.compareAndSet(false, true)) {
            return false;
        }
        log.warn("Webhook 连续 {} 次探测不健康，降级为长轮询接收", healthTracker.failureThreshold());
        modeController.stopWebhook();
        modeController.startLongPolling();
        return true;
    }

    public boolean isDegraded() {
        return degraded.get();
    }
}

package com.tg.heyisheng.bot.core.failover;

/**
 * 连续失败计数器：把「一次次探测结果」聚合成「是否该降级」的判定。
 *
 * <p>与探测方式解耦——不论探测的是 Telegram 的 getWebhookInfo 还是别的手段，
 * 判定规则都一样：<b>连续</b> {@link WebhookHealth#UNHEALTHY} 达阈值才降级；
 * 中途任何一次 {@link WebhookHealth#HEALTHY} 即计数归零（避免误降级）；
 * {@link WebhookHealth#UNKNOWN}（探测通道故障，无可信结论）<b>不改变计数</b>——
 * 它是「没有信息」，既不该推进降级、也不该清零已有的失败累计。
 *
 * <p>线程安全：探测可能来自调度线程，判定可能被管理端点读取。
 */
public class HealthTracker {

    private final int failureThreshold;
    private int consecutiveFailures;

    public HealthTracker(int failureThreshold) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold 必须为正数");
        }
        this.failureThreshold = failureThreshold;
    }

    /**
     * 记录一次探测结果。
     *
     * @return {@code true} 仅当本次为 {@link WebhookHealth#UNHEALTHY} 且连续失败数达到阈值
     */
    public synchronized boolean record(WebhookHealth health) {
        return switch (health) {
            case HEALTHY -> {
                consecutiveFailures = 0;
                yield false;
            }
            case UNHEALTHY -> {
                consecutiveFailures++;
                yield consecutiveFailures >= failureThreshold;
            }
            // 无可信结论：不推进降级，也不清零已有累计
            case UNKNOWN -> false;
        };
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    /** 降级完成或人工恢复后归零。 */
    public synchronized void reset() {
        consecutiveFailures = 0;
    }

    public int failureThreshold() {
        return failureThreshold;
    }
}

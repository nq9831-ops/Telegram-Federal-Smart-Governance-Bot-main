package com.tg.heyisheng.bot.core.failover;

/**
 * 连续失败计数器：把「一次次探测结果」聚合成「是否该降级」的判定。
 *
 * <p>与探测方式解耦——不论探测的是 Telegram 的 getWebhookInfo 还是别的手段，
 * 判定规则都一样：<b>连续</b>失败达阈值才降级；中途任何一次成功即计数归零
 * （避免网络抖动导致误降级）。
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
     * @return {@code true} 表示本次结果使连续失败数达到阈值，应当触发降级
     */
    public synchronized boolean record(boolean healthy) {
        if (healthy) {
            consecutiveFailures = 0;
            return false;
        }
        consecutiveFailures++;
        return consecutiveFailures >= failureThreshold;
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

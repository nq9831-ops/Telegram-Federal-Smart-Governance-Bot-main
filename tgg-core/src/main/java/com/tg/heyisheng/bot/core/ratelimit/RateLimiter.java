package com.tg.heyisheng.bot.core.ratelimit;

/**
 * 限流器：按维度键做配额判定。
 *
 * <p>切片 1 用内存实现（见 {@link InMemoryRateLimiter}）；后续切片切换为 Redis 共享计数，
 * 那时只需替换实现，调用方不变。
 */
public interface RateLimiter {

    /**
     * 尝试获取一次配额。
     *
     * @param key 维度键（如 {@code u:42} / {@code g:-100} / {@code global}）
     * @return {@code true} 放行；{@code false} 超限
     */
    boolean tryAcquire(String key);
}

package com.tg.heyisheng.bot.core.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存滑动窗口限流器。
 *
 * <p>每个维度键维护一个时间戳队列：窗口之外的记录在判定时被剔除，队列长度即窗口内计数。
 * 线程安全（{@link ConcurrentHashMap} + 对每个队列加锁），且时钟可注入以便测试窗口过期行为。
 *
 * <p><b>切片边界</b>：本实现不跨进程——多实例部署时不共享配额。切片 1 的 Bot 是单实例，
 * 足够；切换 Redis 时替换本类即可。
 */
public class InMemoryRateLimiter implements RateLimiter {

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(int limit, Duration window) {
        this(limit, window, Clock.systemUTC());
    }

    public InMemoryRateLimiter(int limit, Duration window, Clock clock) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window 必须为正时长");
        }
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    @Override
    public boolean tryAcquire(String key) {
        Instant now = clock.instant();
        Instant threshold = now.minus(window);

        Deque<Instant> timestamps = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && !timestamps.peekFirst().isAfter(threshold)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= limit) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }
}

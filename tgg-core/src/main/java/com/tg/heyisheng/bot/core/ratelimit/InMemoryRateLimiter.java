package com.tg.heyisheng.bot.core.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** 每这么多次调用触发一次陈旧键清理——摊销成本，避免每次调用都做 O(n) 扫描。 */
    static final int PURGE_INTERVAL = 1024;

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final AtomicInteger callsSincePurge = new AtomicInteger();

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

        if (callsSincePurge.incrementAndGet() >= PURGE_INTERVAL) {
            callsSincePurge.set(0);
            purgeStale();
        }

        Deque<Instant> timestamps = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            drainExpired(timestamps, threshold);
            if (timestamps.size() >= limit) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    /**
     * 清理**陈旧键**：窗口内已无活动的键从 map 中移除。
     *
     * <p><b>为什么需要它</b>：时间戳只在「再次访问该键」时才被清理，因此一个只发过一条消息、
     * 之后再也不出现的用户/群，其键会永久留在 map 里——长跑（尤其用户维度）会无界增长。
     *
     * <p>代价说明：本方法与 {@link #tryAcquire} 并发时，理论上可能移除一个刚被
     * {@code computeIfAbsent} 取到、尚未写入的队列，导致该次计数丢失（少算一次限流）。
     * 概率极低且后果轻微（限流是保护而非门禁），换取的是内存有界。
     *
     * @return 被移除的键数
     */
    int purgeStale() {
        Instant threshold = clock.instant().minus(window);
        int before = hits.size();
        hits.entrySet().removeIf(entry -> drainExpired(entry.getValue(), threshold));
        return before - hits.size();
    }

    /** 丢弃窗口之外的记录；返回清理后队列是否为空。 */
    private static boolean drainExpired(Deque<Instant> timestamps, Instant threshold) {
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && !timestamps.peekFirst().isAfter(threshold)) {
                timestamps.pollFirst();
            }
            return timestamps.isEmpty();
        }
    }

    /** 当前跟踪的键数（便于测试与诊断内存占用）。 */
    int trackedKeys() {
        return hits.size();
    }
}

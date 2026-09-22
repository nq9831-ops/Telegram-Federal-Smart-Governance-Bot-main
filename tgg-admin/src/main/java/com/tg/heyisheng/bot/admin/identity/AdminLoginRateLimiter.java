package com.tg.heyisheng.bot.admin.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录限流（模块十一 · 护栏）——按来源 IP 的滑动窗口。
 *
 * <p><b>为什么在「账号锁定」之外还要 IP 限流</b>：账号锁定是<b>单账号</b>维度，挡不住
 * 「拿一堆用户名各试几次」的跨账号爆破。IP 限流补上这一维。
 *
 * <p><b>内存实现、进程内</b>：多副本部署下每个副本各记一份（挡得住单副本高频爆破这一最常见形态）；
 * 分布式严格限流需共享存储（Redis 等），属部署侧增强——本类不假装拥有它。
 */
public class AdminLoginRateLimiter {

    /** 每这么多次调用触发一次陈旧键清理——摊销成本，避免每次调用都做 O(n) 扫描（同 {@code InMemoryRateLimiter}）。 */
    static final int PURGE_INTERVAL = 1024;

    private final int maxPerWindow;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final AtomicInteger callsSincePurge = new AtomicInteger();

    public AdminLoginRateLimiter(int maxPerWindow, Duration window, Clock clock) {
        this.maxPerWindow = Math.max(1, maxPerWindow);
        this.window = window;
        this.clock = clock;
    }

    /**
     * 尝试为某个 key（如来源 IP）取得一次配额。
     *
     * @return 允许则 true；超限则 false
     */
    public boolean tryAcquire(String key) {
        Instant now = clock.instant();

        if (callsSincePurge.incrementAndGet() >= PURGE_INTERVAL) {
            callsSincePurge.set(0);
            purgeStale();
        }

        Deque<Instant> timestamps = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            drainExpired(timestamps, now.minus(window));
            if (timestamps.size() >= maxPerWindow) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    /**
     * 清理**陈旧键**：窗口内已无活动的键从 map 中移除（同 {@code InMemoryRateLimiter#purgeStale} 的模式）。
     *
     * <p><b>为什么需要它</b>：时间戳只在「再次访问该键」时被清理，因此一个只失败过一次、
     * 之后再也不出现的来源 IP，其键会永久留在 map 里——长跑（尤其被扫描器遍历 IP 时）会无界增长。
     *
     * <p>代价说明：本方法与 {@link #tryAcquire} 并发时，理论上可能移除一个刚被
     * {@code computeIfAbsent} 取到、尚未写入的队列，导致该次计数丢失（少算一次限流）。
     * 概率极低且后果轻微（限流是保护而非门禁），换取的是内存有界。
     *
     * @return 被移除的键数
     */
    int purgeStale() {
        Instant cutoff = clock.instant().minus(window);
        int before = hits.size();
        hits.entrySet().removeIf(entry -> drainExpired(entry.getValue(), cutoff));
        return before - hits.size();
    }

    /** 丢弃窗口之外的记录；返回清理后队列是否为空。窗口口径与 {@link #tryAcquire} 逐字一致。 */
    private static boolean drainExpired(Deque<Instant> timestamps, Instant cutoff) {
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
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

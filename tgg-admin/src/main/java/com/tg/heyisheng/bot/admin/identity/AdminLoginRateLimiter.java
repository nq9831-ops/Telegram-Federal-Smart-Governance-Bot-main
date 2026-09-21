package com.tg.heyisheng.bot.admin.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private final int maxPerWindow;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

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
        Deque<Instant> timestamps = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            Instant cutoff = now.minus(window);
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxPerWindow) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }
}

package com.tg.heyisheng.bot.core.notify;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通知频率门（模块十 §11.1）——按「收件人 × 级别」各自计额度。
 *
 * <p><b>为什么是内存实现而不是 Redis</b>：V5.0 原文写的是 Redis 计数，但本项目<b>未引 Redis</b>
 * （限流走内存实现，见 {@code InMemoryRateLimiter}），为一个计数器引入基础设施依赖不划算。
 * 多实例部署时的一致性属部署侧课题，与既有 {@code TaughtRuleService} 缓存的取舍一致。
 *
 * <p><b>超限不丢弃</b>：被抑制的条数会累计，并在下次额度可用时作为「摘要条数」返回，
 * 由 {@link NotificationDispatcher} 合并成一条——原文要求「超限时合并为摘要发送」，
 * 静默丢弃会让用户永远不知道发生过什么。
 *
 * <p>时钟可注入：窗口滚动（小时 / 天）是本节最易出错的部分，用可推进的 {@link Clock} 单测它，
 * 而不是真等一小时。
 */
public class NotificationRateLimiter {

    /** 一次准入判定的结果。 */
    public record Decision(boolean allowed, int mergedCount) {
    }

    /** 每这么多次判定触发一次陈旧条目清理——摊销成本，避免每次判定都做 O(n) 扫描。 */
    static final int PURGE_INTERVAL = 1024;

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger callsSincePurge = new AtomicInteger();

    public NotificationRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * 判定该级别、该收件人此刻是否可以发送。
     *
     * @return 允许时 {@code allowed=true}，并带上「此前被抑制、本次应合并的条数」
     */
    public Decision decide(NotificationLevel level, long recipientId) {
        if (level.unlimited()) {
            return new Decision(true, 0);
        }
        if (callsSincePurge.incrementAndGet() >= PURGE_INTERVAL) {
            callsSincePurge.set(0);
            purgeStale();
        }
        Window window = windows.computeIfAbsent(key(level, recipientId), ignored -> new Window());
        synchronized (window) {
            Instant now = clock.instant();
            window.rollHourIfNeeded(now);
            window.rollDayIfNeeded(now);

            boolean withinHour = window.hourCount < level.perHour();
            boolean withinDay = level.perDay() <= 0 || window.dayCount < level.perDay();
            if (withinHour && withinDay) {
                window.hourCount++;
                window.dayCount++;
                int merged = window.suppressed;
                window.suppressed = 0;
                return new Decision(true, merged);
            }
            window.suppressed++;
            return new Decision(false, 0);
        }
    }

    private static String key(NotificationLevel level, long recipientId) {
        return level.name() + ':' + recipientId;
    }

    /**
     * 清理**陈旧条目**：小时窗与天窗都已滚动、且无待合并计数的（收件人 × 级别）条目从 map 移除。
     *
     * <p><b>为什么需要它</b>：{@link Window} 的计数是<b>惰性滚动</b>的——一个只收到过一次通知、
     * 之后再不出现的收件人，其条目会永久留在 map 里，长跑下随历史收件人集合无界增长。
     *
     * <p><b>为什么不会误发配额</b>：仅当<b>两个窗口都已滚动</b>（下次访问本就会把 hourCount 与
     * dayCount 都清零）且 {@code suppressed==0} 时才移除——删除与「滚动到新窗口」等价，
     * 不会给任何收件人多发额度。
     *
     * @return 被移除的条目数
     */
    int purgeStale() {
        Instant now = clock.instant();
        Instant currentHour = now.truncatedTo(ChronoUnit.HOURS);
        Instant currentDay = now.truncatedTo(ChronoUnit.DAYS);
        int before = windows.size();
        windows.entrySet().removeIf(entry -> {
            Window w = entry.getValue();
            synchronized (w) {
                return w.suppressed == 0
                        && !w.hourStart.equals(currentHour)
                        && !w.dayStart.equals(currentDay);
            }
        });
        return before - windows.size();
    }

    /** 当前跟踪的（收件人 × 级别）条目数（便于测试与诊断内存占用）。 */
    int trackedWindows() {
        return windows.size();
    }

    /** 单个（收件人 × 级别）的窗口计数。 */
    private static final class Window {
        private Instant hourStart = Instant.EPOCH;
        private Instant dayStart = Instant.EPOCH;
        private int hourCount;
        private int dayCount;
        private int suppressed;

        private void rollHourIfNeeded(Instant now) {
            Instant currentHour = now.truncatedTo(ChronoUnit.HOURS);
            if (!currentHour.equals(hourStart)) {
                hourStart = currentHour;
                hourCount = 0;
            }
        }

        private void rollDayIfNeeded(Instant now) {
            Instant currentDay = now.truncatedTo(ChronoUnit.DAYS);
            if (!currentDay.equals(dayStart)) {
                dayStart = currentDay;
                dayCount = 0;
            }
        }
    }
}

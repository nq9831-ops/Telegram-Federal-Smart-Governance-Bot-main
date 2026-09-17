package com.tg.heyisheng.bot.core.notify;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

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

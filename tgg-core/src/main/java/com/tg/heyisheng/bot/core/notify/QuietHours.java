package com.tg.heyisheng.bot.core.notify;

import java.time.LocalTime;

/**
 * 免打扰时段（模块十 §11.1）——纯值对象，只管「某时刻是否落在时段内」。
 *
 * <p><b>跨午夜是常态而非边角</b>：用户写「22:00-08:00」时 {@code start > end}，
 * 此时区间是「start 到午夜」∪「午夜到 end」。把它当普通区间判会得出「永远不静默」的错误结论
 * ——故单独成类并配边界测试。
 *
 * @param start 起始（含）
 * @param end   结束（不含）
 */
public record QuietHours(LocalTime start, LocalTime end) {

    public QuietHours {
        if (start == null || end == null) {
            throw new IllegalArgumentException("免打扰时段起止均不可为空");
        }
        if (start.equals(end)) {
            throw new IllegalArgumentException("免打扰时段起止不得相同（无法表达任何区间）");
        }
    }

    /** 该时刻是否处于免打扰时段。 */
    public boolean covers(LocalTime now) {
        if (now == null) {
            return false;
        }
        return start.isBefore(end)
                ? !now.isBefore(start) && now.isBefore(end)   // 同日区间，如 12:00-14:00
                : !now.isBefore(start) || now.isBefore(end);  // 跨午夜，如 22:00-08:00
    }
}

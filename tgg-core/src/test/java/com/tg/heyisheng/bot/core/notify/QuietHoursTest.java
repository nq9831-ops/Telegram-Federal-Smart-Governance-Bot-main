package com.tg.heyisheng.bot.core.notify;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 免打扰时段判定测试（模块十 §11.1）——纯值对象，边界是重点。
 *
 * <p><b>跨午夜是常态</b>：用户写「22:00-08:00」时 start &gt; end。把它当普通区间判会得出
 * 「永远不静默」的错误结论——那意味着用户半夜照收通知，功能形同不存在。
 */
class QuietHoursTest {

    @Test
    void sameDayWindowIncludesStartExcludesEnd() {
        QuietHours hours = new QuietHours(LocalTime.of(12, 0), LocalTime.of(14, 0));

        assertThat(hours.covers(LocalTime.of(11, 59))).isFalse();
        assertThat(hours.covers(LocalTime.of(12, 0))).as("起点含").isTrue();
        assertThat(hours.covers(LocalTime.of(13, 59))).isTrue();
        assertThat(hours.covers(LocalTime.of(14, 0))).as("终点不含").isFalse();
    }

    @Test
    void crossMidnightWindowIsHandled() {
        QuietHours hours = new QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0));

        assertThat(hours.covers(LocalTime.of(23, 0))).isTrue();
        assertThat(hours.covers(LocalTime.of(3, 0))).as("跨午夜后仍属静默").isTrue();
        assertThat(hours.covers(LocalTime.of(7, 59))).isTrue();
        assertThat(hours.covers(LocalTime.of(8, 0))).isFalse();
        assertThat(hours.covers(LocalTime.of(12, 0))).isFalse();
    }

    @Test
    void rejectsEmptyWindow() {
        assertThatThrownBy(() -> new QuietHours(LocalTime.NOON, LocalTime.NOON))
                .as("起止相同的区间无意义，必须拒")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandParsesValidWindow() {
        QuietHours parsed = QuietHoursCommandHandler.parse("22:00-08:00");

        assertThat(parsed).isNotNull();
        assertThat(parsed.start()).isEqualTo(LocalTime.of(22, 0));
        assertThat(parsed.end()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void commandRejectsMalformedInput() {
        assertThat(QuietHoursCommandHandler.parse("22:00")).as("缺终止时刻").isNull();
        assertThat(QuietHoursCommandHandler.parse("22点-8点")).isNull();
        assertThat(QuietHoursCommandHandler.parse("25:00-08:00")).isNull();
        assertThat(QuietHoursCommandHandler.parse("22:00-22:00")).as("空区间").isNull();
    }
}

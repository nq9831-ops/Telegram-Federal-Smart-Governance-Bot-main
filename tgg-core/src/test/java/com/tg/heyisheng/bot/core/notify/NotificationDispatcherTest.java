package com.tg.heyisheng.bot.core.notify;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知三级分类与频率门测试（模块十 §11.1）。
 *
 * <p><b>规格（V5.0 原文）</b>：紧急通知（封禁 / 硬红线处罚 / 解封 / 信用分恢复）<b>不受频率限制</b>；
 * 重要通知（信用分变动 / 申诉进度 / 群组状态变更）<b>每小时最多 3 条</b>；
 * 普通通知（推荐更新 / 系统公告 / 教学贡献）<b>每小时最多 1 条、每天最多 5 条</b>；
 * <b>超限时合并为摘要发送</b>。
 *
 * <p>窗口滚动用可注入的 {@link Clock} 驱动，不真等一小时——照本项目「接缝可注入」的既有纪律
 * （如 listing 的 {@code Sleeper} 替身）。
 */
class NotificationDispatcherTest {

    private static final long USER = 777L;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
    private final List<Long> recipients = new ArrayList<>();
    private final List<String> sent = new ArrayList<>();
    private final NotificationRateLimiter limiter = new NotificationRateLimiter(clock);
    private final NotificationDispatcher dispatcher = new NotificationDispatcher(
            (id, text) -> {
                recipients.add(id);
                sent.add(text);
            }, limiter);

    @Test
    void urgentIsNeverRateLimited() {
        for (int i = 0; i < 20; i++) {
            dispatcher.notify(new Notification(NotificationLevel.URGENT, USER, "封禁通知 " + i));
        }

        assertThat(sent).as("紧急通知不受频率限制").hasSize(20);
    }

    @Test
    void importantAllowsThreePerHourThenSuppresses() {
        for (int i = 1; i <= 4; i++) {
            dispatcher.notify(new Notification(NotificationLevel.IMPORTANT, USER, "信用分变动 " + i));
        }

        assertThat(sent).as("重要通知：每小时最多 3 条").hasSize(3);
    }

    @Test
    void normalAllowsOnePerHour() {
        for (int i = 1; i <= 3; i++) {
            dispatcher.notify(new Notification(NotificationLevel.NORMAL, USER, "系统公告 " + i));
        }

        assertThat(sent).as("普通通知：每小时最多 1 条").hasSize(1);
    }

    @Test
    void normalHasDailyCapOfFive() {
        for (int hour = 0; hour < 8; hour++) {
            dispatcher.notify(new Notification(NotificationLevel.NORMAL, USER, "公告 " + hour));
            clock.advance(Duration.ofHours(1));
        }

        assertThat(sent).as("普通通知：每天最多 5 条（跨小时也不放宽）").hasSize(5);
    }

    @Test
    void suppressedOnesAreMergedIntoSummaryOnNextSend() {
        dispatcher.notify(new Notification(NotificationLevel.IMPORTANT, USER, "重要1"));
        dispatcher.notify(new Notification(NotificationLevel.IMPORTANT, USER, "重要2"));
        dispatcher.notify(new Notification(NotificationLevel.IMPORTANT, USER, "重要3"));
        dispatcher.notify(new Notification(NotificationLevel.IMPORTANT, USER, "被抑制A"));
        dispatcher.notify(new Notification(NotificationLevel.IMPORTANT, USER, "被抑制B"));
        assertThat(sent).as("额度内 3 条，超出的 2 条被抑制").hasSize(3);

        clock.advance(Duration.ofHours(1));
        dispatcher.notify(new Notification(NotificationLevel.IMPORTANT, USER, "下一小时第一条"));

        assertThat(sent).hasSize(4);
        assertThat(sent.get(3))
                .as("超限条数应合并为摘要附在下一次发送上，而不是静默丢失")
                .contains("下一小时第一条")
                .contains("另有 2 条");
    }

    @Test
    void eachRecipientHasItsOwnQuota() {
        dispatcher.notify(new Notification(NotificationLevel.IMPORTANT, USER, "A1"));
        dispatcher.notify(new Notification(NotificationLevel.IMPORTANT, USER + 1, "B1"));

        assertThat(recipients).as("频率额度按收件人各自计").containsExactly(USER, USER + 1);
    }

    /** 可推进的时钟：让「小时 / 天窗口滚动」可测（照本项目「接缝可注入」的纪律）。 */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

package com.tg.heyisheng.bot.core.admission;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.objects.ChatPermissions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 观察期测试。
 *
 * <p><b>最关键的一条是 {@code untilDate} 必须非空</b>：`RestrictChatMember` 不带 `untilDate`
 * 就是**永久限制**——新成员将永远无法正常发言。这与上一轮 `BanChatMember` 漏设 `untilDate`
 * 导致永久封禁是同一个坑，用测试锁死。
 */
class ObservationPeriodTest {

    private static final long CHAT = -100L;
    private static final long USER = 42L;
    private static final Duration SEVEN_DAYS = Duration.ofDays(7);

    private static List<BotApiMethod<?>> applyWith(Duration period) {
        List<BotApiMethod<?>> sent = new ArrayList<>();
        new ObservationPeriodService(sent::add, period).apply(CHAT, USER);
        return sent;
    }

    @Test
    void appliesRestrictionWithUntilDate() {
        List<BotApiMethod<?>> sent = applyWith(SEVEN_DAYS);

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).isInstanceOf(RestrictChatMember.class);
        RestrictChatMember restrict = (RestrictChatMember) sent.get(0);

        assertThat(restrict.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(restrict.getUserId()).isEqualTo(USER);
        assertThat(restrict.getUntilDate())
                .as("必须设 untilDate——不设即永久限制发言，是上一轮 BanChatMember 同源的坑")
                .isNotNull();

        long expected = Instant.now().getEpochSecond() + SEVEN_DAYS.toSeconds();
        assertThat(restrict.getUntilDate().longValue())
                .as("untilDate 应约为 now + 观察期")
                .isBetween(expected - 10, expected + 10);
    }

    /** 观察期设为 0 表示不启用——不得产出任何限制。 */
    @Test
    void skipsRestrictionWhenPeriodIsZero() {
        assertThat(applyWith(Duration.ZERO)).isEmpty();
    }

    @Test
    void skipsRestrictionWhenPeriodIsNegative() {
        assertThat(applyWith(Duration.ofSeconds(-1))).isEmpty();
    }

    /** 温和限制：可发文字，但禁媒体/其他消息/网页预览/邀请。 */
    @Test
    void mildRestrictionAllowsTextAndBlocksAbuseChannels() {
        RestrictChatMember restrict = (RestrictChatMember) applyWith(SEVEN_DAYS).get(0);
        ChatPermissions permissions = restrict.getPermissions();

        assertThat(permissions).as("permissions 是必填项").isNotNull();
        assertThat(permissions.getCanSendMessages()).as("允许发文字（温和限制）").isTrue();
        assertThat(permissions.getCanSendPhotos()).as("禁图片").isFalse();
        assertThat(permissions.getCanSendVideos()).as("禁视频").isFalse();
        assertThat(permissions.getCanSendOtherMessages()).as("禁贴纸/GIF 等").isFalse();
        assertThat(permissions.getCanAddWebPagePreviews()).as("禁链接预览——广告的主要载体").isFalse();
        assertThat(permissions.getCanInviteUsers()).as("观察期内不得拉人").isFalse();
    }

    /** 参数不合法时不得抛异常（入群链路不能被它打断）。 */
    @Test
    void toleratesMissingIdentity() {
        List<BotApiMethod<?>> sent = new ArrayList<>();
        ObservationPeriodService service = new ObservationPeriodService(sent::add, SEVEN_DAYS);

        service.apply(null, USER);
        service.apply(CHAT, null);

        assertThat(sent).isEmpty();
    }
}

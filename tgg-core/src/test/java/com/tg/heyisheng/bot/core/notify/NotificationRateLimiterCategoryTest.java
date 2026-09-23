package com.tg.heyisheng.bot.core.notify;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知配额<b>分池</b>守卫（模块十二引入的类别维）。
 *
 * <p><b>它守的是什么</b>：若交易通知与治理通知共用额度池，交易量大时封禁告知、信用分变动等
 * <b>权益通知</b>会被静默抑制——用户永远不知道自己被封了，而日志、测试、指标都不报错。
 * 这是"缺席不会自己报警"的典型，故用可推进时钟把两池的独立性钉死。
 */
class NotificationRateLimiterCategoryTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");
    private final NotificationRateLimiter limiter =
            new NotificationRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void escrowTrafficDoesNotConsumeGovernanceQuota() {
        // 把治理侧 NORMAL 额度用光（1 条/小时）
        assertThat(limiter.decide(NotificationCategory.GOVERNANCE, NotificationLevel.NORMAL, 42L).allowed())
                .isTrue();
        assertThat(limiter.decide(NotificationCategory.GOVERNANCE, NotificationLevel.NORMAL, 42L).allowed())
                .as("同类别第 2 条应被小时窗挡下（既有语义不变）")
                .isFalse();

        // 交易类别同级别仍可发——两池独立
        assertThat(limiter.decide(NotificationCategory.ESCROW, NotificationLevel.NORMAL, 42L).allowed())
                .as("交易通知必须有自己的额度池，不得被治理侧用量耗尽")
                .isTrue();
    }

    @Test
    void governanceTrafficDoesNotConsumeEscrowQuota() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.decide(NotificationCategory.ESCROW, NotificationLevel.IMPORTANT, 7L).allowed())
                    .as("交易侧 IMPORTANT 第 %d 条（额度 3/小时）", i + 1)
                    .isTrue();
        }
        assertThat(limiter.decide(NotificationCategory.ESCROW, NotificationLevel.IMPORTANT, 7L).allowed())
                .as("交易侧第 4 条应被挡下")
                .isFalse();

        assertThat(limiter.decide(NotificationCategory.GOVERNANCE, NotificationLevel.IMPORTANT, 7L).allowed())
                .as("治理侧额度独立，不受交易侧用满影响")
                .isTrue();
    }

    @Test
    void legacyEntryPointSharesGovernancePoolRatherThanOpeningANewOne() {
        assertThat(limiter.decide(NotificationLevel.NORMAL, 99L).allowed()).isTrue();

        assertThat(limiter.decide(NotificationCategory.GOVERNANCE, NotificationLevel.NORMAL, 99L).allowed())
                .as("不带类别的既有入口必须与 GOVERNANCE 同池——否则既有调用者会凭空多出一份额度")
                .isFalse();
    }

    @Test
    void urgentStaysUnlimitedAcrossCategories() {
        assertThat(limiter.decide(NotificationCategory.ESCROW, NotificationLevel.URGENT, 5L).allowed()).isTrue();
        assertThat(limiter.decide(NotificationCategory.ESCROW, NotificationLevel.URGENT, 5L).allowed()).isTrue();
        assertThat(limiter.decide(NotificationCategory.GOVERNANCE, NotificationLevel.URGENT, 5L).allowed()).isTrue();
    }
}

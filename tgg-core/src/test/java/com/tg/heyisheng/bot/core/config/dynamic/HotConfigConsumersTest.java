package com.tg.heyisheng.bot.core.config.dynamic;

import com.tg.heyisheng.bot.core.membership.MemberJoinObservation;
import com.tg.heyisheng.bot.core.membership.MemberJoinObservationRepository;
import com.tg.heyisheng.bot.core.membership.MembershipDurationTeachGate;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository;
import com.tg.heyisheng.bot.core.moderation.RedLineReviewSla;
import com.tg.heyisheng.bot.core.moderation.ReviewStatus;
import com.tg.heyisheng.bot.core.moderation.SensitiveTopicStrikeRepository;
import com.tg.heyisheng.bot.core.notify.NotificationDispatcher;
import com.tg.heyisheng.bot.core.permission.ConfigBackedRoleSource;
import com.tg.heyisheng.bot.core.permission.Role;
import com.tg.heyisheng.bot.core.retention.RetentionProperties;
import com.tg.heyisheng.bot.core.retention.RetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行参数「<b>热生效</b>」的行为实证。
 *
 * <p>为什么必须逐个断言「不重启即生效」：把消费方从「构造期捕获」改为「调用期读取」是一次
 * <b>看不见的重构</b>——若哪天有人图省事又把值改回构造参数，编译照样过、总览照样显示「热生效」，
 * 而实际行为退化回「需重启」。故这里的判据是<b>行为</b>：改了配置服务里的覆盖值，
 * 同一个实例的下一次调用必须用新值。
 */
class HotConfigConsumersTest {

    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long CHAT = -100L;
    private static final long USER = 42L;

    private static RuntimeConfigService config() {
        return new RuntimeConfigService(mock(ConfigOverrideRepository.class), new MockEnvironment(), CLOCK);
    }

    @Test
    void moderationReviewGuardIsHot() {
        RuntimeConfigService cfg = config();
        ModerationReviewGuard guard = new ModerationReviewGuard(cfg);
        assertThat(guard.isReviewer(USER)).isFalse();

        cfg.set(ModerationReviewGuard.KEY, "42,43", null);

        assertThat(guard.isReviewer(USER)).as("改复核人名单后无需重启即生效").isTrue();
        assertThat(guard.size()).isEqualTo(2);
    }

    @Test
    void redLineSlaIsHot() {
        RuntimeConfigService cfg = config();
        ModerationReviewRepository repo = mock(ModerationReviewRepository.class);
        when(repo.findByHardLineTrueAndStatusAndCreatedAtBefore(eq(ReviewStatus.PENDING), any()))
                .thenReturn(List.of());
        RedLineReviewSla sla = new RedLineReviewSla(repo, new ModerationReviewGuard("900"),
                mock(NotificationDispatcher.class), CLOCK, cfg);

        cfg.set(RedLineReviewSla.SLA_KEY, "5", null);
        sla.alarm();

        verify(repo).findByHardLineTrueAndStatusAndCreatedAtBefore(
                ReviewStatus.PENDING, NOW.minus(Duration.ofHours(5)));
    }

    @Test
    void teachGateMinimumIsHot() {
        RuntimeConfigService cfg = config();
        MemberJoinObservationRepository repo = mock(MemberJoinObservationRepository.class);
        when(repo.findByChatIdAndUserId(CHAT, USER)).thenReturn(Optional.of(
                new MemberJoinObservation(CHAT, USER, NOW.minus(Duration.ofDays(5)),
                        NOW.minus(Duration.ofDays(5)))));
        MembershipDurationTeachGate gate = new MembershipDurationTeachGate(repo, CLOCK, cfg);

        assertThat(gate.rejectionFor(CHAT, USER)).as("默认 30 天：入群 5 天不满足").isPresent();

        cfg.set("tgg.teach.min-membership-days", "1", null);

        assertThat(gate.rejectionFor(CHAT, USER)).as("改成 1 天后即放行（无需重启）").isEmpty();
    }

    @Test
    void retentionDaysAreHot() {
        RuntimeConfigService cfg = config();
        RetentionService service = new RetentionService(
                mock(ModerationReviewRepository.class),
                mock(SensitiveTopicStrikeRepository.class),
                mock(MemberJoinObservationRepository.class),
                new RetentionProperties(), CLOCK, cfg);

        cfg.set("tgg.retention.reviewed-queue-days", "7", null);

        assertThat(service.report().get(0).rule())
                .as("报告口径应跟随新天数（不是构造期的 90）")
                .contains("7 天前");
    }

    @Test
    void roleSourceIsHot() {
        RuntimeConfigService cfg = config();
        ConfigBackedRoleSource source = new ConfigBackedRoleSource(cfg);
        assertThat(source.roleOf(CHAT, USER)).isEqualTo(Role.MEMBER);

        cfg.set(ConfigBackedRoleSource.KEY, "-100:42:ADMIN", null);

        assertThat(source.roleOf(CHAT, USER)).as("改群内授权后无需重启即生效").isEqualTo(Role.ADMIN);
    }

    /** 热化的代价是解析推迟到调用期——必须靠**写入校验**补回 fail-fast（格式非法即拒）。 */
    @Test
    void malformedRoleGrantsAreRejectedOnWrite() {
        RuntimeConfigService cfg = config();

        assertThatThrownBy(() -> cfg.set(ConfigBackedRoleSource.KEY, "-100:42:no-such-role", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("授权串格式非法");
    }
}

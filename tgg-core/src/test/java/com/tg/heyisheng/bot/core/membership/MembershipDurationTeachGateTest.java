package com.tg.heyisheng.bot.core.membership;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「入群时长」门槛（模块九 §10.3）。
 *
 * <p>两个必须钉死的语义：
 * <ol>
 *   <li><b>边界</b>：恰好满 30 天要<b>放行</b>（判据是 {@code >=}）——写成 {@code >} 会让
 *       「刚好满 30 天」的用户被拒，而这正是最容易被抱怨的一格。</li>
 *   <li><b>无记录放行</b>：这不是「偷懒」，而是 Telegram 侧的硬约束——历史入群时间不可回收，
 *       拒绝会让所有在 bot 上线前入群的成员永久失去教学资格。故用显式测试把它固定下来，
 *       避免后人「顺手改成更严格」而不知其代价。</li>
 * </ol>
 */
class MembershipDurationTeachGateTest {

    private static final long CHAT = -100900999L;
    private static final long USER = 777L;
    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");
    private static final Duration THIRTY_DAYS = Duration.ofDays(30);

    private final MemberJoinObservationRepository repository = mock(MemberJoinObservationRepository.class);
    private final MembershipDurationTeachGate gate =
            new MembershipDurationTeachGate(repository, Clock.fixed(NOW, ZoneOffset.UTC), THIRTY_DAYS);

    private void observedJoiningAt(Instant joinedAt) {
        when(repository.findByChatIdAndUserId(CHAT, USER))
                .thenReturn(Optional.of(new MemberJoinObservation(CHAT, USER, joinedAt, joinedAt)));
    }

    @Test
    void joinedExactlyTheMinimumAllows() {
        observedJoiningAt(NOW.minus(THIRTY_DAYS));

        assertThat(gate.rejectionFor(CHAT, USER)).isEmpty();
    }

    @Test
    void joinedLongAgoAllows() {
        observedJoiningAt(NOW.minus(Duration.ofDays(200)));

        assertThat(gate.rejectionFor(CHAT, USER)).isEmpty();
    }

    @Test
    void joinedOneDayShortOfTheMinimumRejects() {
        observedJoiningAt(NOW.minus(Duration.ofDays(29)));

        assertThat(gate.rejectionFor(CHAT, USER))
                .hasValueSatisfying(reason -> assertThat(reason).contains("30 天"));
    }

    @Test
    void joinedMomentsAgoRejects() {
        observedJoiningAt(NOW.minus(Duration.ofSeconds(1)));

        assertThat(gate.rejectionFor(CHAT, USER)).isPresent();
    }

    @Test
    void unknownMembershipAllowsBecauseHistoryIsUnrecoverable() {
        when(repository.findByChatIdAndUserId(CHAT, USER)).thenReturn(Optional.empty());

        assertThat(gate.rejectionFor(CHAT, USER)).isEmpty();
    }

    @Test
    void unknownUserIsRejectedWithoutQueryingTheLedger() {
        assertThat(gate.rejectionFor(CHAT, null)).isPresent();

        verifyNoInteractions(repository);
    }
}

package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.core.moderation.SensitiveTopicStrikeService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「无违规」门槛（模块九 §10.3）——core 能独立实现的那一条。
 *
 * <p>守的不变量：0 次违规放行；≥1 次拒绝<b>且说出次数</b>（管理员要能自证）；
 * 身份不可识别时拒绝且<b>不触碰计数</b>（否则 userId 拆箱会 NPE）。
 */
class NoViolationTeachGateTest {

    private static final long CHAT = -100900999L;
    private static final long USER = 777L;

    private final SensitiveTopicStrikeService strikeService = mock(SensitiveTopicStrikeService.class);
    private final NoViolationTeachGate gate = new NoViolationTeachGate(strikeService);

    @Test
    void noStrikeAllows() {
        when(strikeService.countOf(CHAT, USER)).thenReturn(0);

        assertThat(gate.rejectionFor(CHAT, USER)).isEmpty();
    }

    @Test
    void anyStrikeRejectsAndTellsHowMany() {
        when(strikeService.countOf(CHAT, USER)).thenReturn(2);

        assertThat(gate.rejectionFor(CHAT, USER))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("无违规记录")
                        .contains("2"));
    }

    @Test
    void unknownUserIsRejectedWithoutTouchingTheCounter() {
        assertThat(gate.rejectionFor(CHAT, null)).isPresent();

        verifyNoInteractions(strikeService);
    }
}

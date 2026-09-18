package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code /teach} 的<b>教学门槛</b>测试（模块九 §10.3）。
 *
 * <p><b>为什么门槛要抽成 seam</b>：门槛里最硬的一项是「信用分」，而信用分在 {@code tgg-credit}，
 * {@code tgg-core} 处于依赖链底层、<b>物理上看不到它</b>（与模块九「联邦标记」同一类约束）。
 * 故 core 定义接口、由上层提供实现——默认实现必须「放行」，让未接线时行为与升级前逐字一致。
 *
 * <p><b>本类守的行为</b>：门槛拒绝时<b>不得落库</b>——否则「拒绝了但规则已经生效」是最坏的结果
 * （管理员看到失败提示，规则却在群里跑）。
 */
class TeachCommandGateTest {

    private static final long CHAT = -100900999L;
    private static final long USER = 777L;

    private final TaughtRuleService service = mock(TaughtRuleService.class);

    /** 默认让 teach 返回一个真实实体——否则 handler 回显时取 getRuleId() 会 NPE（本次实测踩到）。 */
    @org.junit.jupiter.api.BeforeEach
    void stubTeach() {
        org.mockito.Mockito.when(service.teach(anyLong(), anyString(), anyString(), anyString(),
                        any(), anyBoolean(), anyLong()))
                .thenReturn(new TaughtRule(CHAT, "SCAM_X", "描述", "假空投",
                        com.tg.heyisheng.bot.core.moderation.RiskLevel.MEDIUM, false, USER,
                        java.time.Instant.now()));
    }

    private static UpdateContext ctx() {
        return new UpdateContext(1, USER, CHAT, 5, "teach", "SCAM_X 假空投 描述");
    }

    private static String text(Object reply) {
        return ((SendMessage) reply).getText();
    }

    @Test
    void defaultEligibilityAllowsTeachingUnchanged() {
        TeachCommandHandler handler = new TeachCommandHandler(service, TeachEligibility.allowAll());

        handler.handle(ctx());

        verify(service).teach(anyLong(), anyString(), anyString(), anyString(),
                any(), anyBoolean(), anyLong());
    }

    @Test
    void rejectionBlocksTeachingAndDoesNotPersist() {
        TeachEligibility denying = (chatId, userId) -> Optional.of("信用分不足（教学需要信用良好）");
        TeachCommandHandler handler = new TeachCommandHandler(service, denying);

        String reply = text(handler.handle(ctx()));

        assertThat(reply).contains("规则未生效").contains("信用分不足");
        verify(service, never()).teach(anyLong(), anyString(), anyString(), anyString(),
                any(), anyBoolean(), anyLong());
    }
}

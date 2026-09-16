package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 处置执行器测试。
 *
 * <p>存在意义：判定结果若无消费者，审核在功能上等于零——
 * 本项目已在 RBAC 与功能开关上栽过同一个坑（「接好了但没通电」），
 * 本类是该模式在审核链路上的护栏。
 */
class ModerationEnforcerTest {

    private static final long CHAT_ID = -100L;
    private static final int MESSAGE_ID = 55;

    private final ModerationEnforcer enforcer = new ModerationEnforcer();

    @Test
    void deletesMessageWhenVerdictNeedsReview() {
        UpdateContext ctx = ctxWithVerdict(new ModerationVerdict(RiskLevel.LOW, false, List.of("SPAM_CASINO")));

        Optional<BotApiMethod<?>> action = enforcer.enforce(ctx);

        assertThat(action).isPresent();
        assertThat(action.get()).isInstanceOf(DeleteMessage.class);
        DeleteMessage delete = (DeleteMessage) action.get();
        assertThat(delete.getChatId()).as("必须定位到正确的群").isEqualTo(String.valueOf(CHAT_ID));
        assertThat(delete.getMessageId()).as("必须定位到正确的消息").isEqualTo(MESSAGE_ID);
    }

    @Test
    void doesNothingForCleanContent() {
        UpdateContext ctx = ctxWithVerdict(ModerationVerdict.clean());

        assertThat(enforcer.enforce(ctx)).isEmpty();
    }

    /** 未挂载判定（未启用审核）时不得处置——否则会把未审内容当成违规删掉。 */
    @Test
    void doesNothingWhenNoVerdictAttached() {
        UpdateContext ctx = new UpdateContext(1, 42L, CHAT_ID, MESSAGE_ID, null);

        assertThat(enforcer.enforce(ctx)).isEmpty();
    }

    /** 硬红线同样走删除（冻结动作是后续增量，但删除必须立刻生效）。 */
    @Test
    void hardLineIsAlsoDeletedImmediately() {
        UpdateContext ctx = ctxWithVerdict(
                new ModerationVerdict(RiskLevel.HIGH, true, List.of("HARD_SECRET_PHRASE")));

        assertThat(enforcer.enforce(ctx)).isPresent();
    }

    /** 缺少 messageId 时无法定位目标：应放弃处置而非抛异常中断整条链路。 */
    @Test
    void skipsWhenMessageIdMissing() {
        UpdateContext ctx = new UpdateContext(1, 42L, CHAT_ID, null, null);
        ctx.attach(new ModerationVerdict(RiskLevel.HIGH, false, List.of("SPAM_CASINO")));

        assertThat(enforcer.enforce(ctx)).isEmpty();
    }

    @Test
    void toleratesNullContext() {
        assertThat(enforcer.enforce(null)).isEmpty();
    }

    private static UpdateContext ctxWithVerdict(ModerationVerdict verdict) {
        UpdateContext ctx = new UpdateContext(1, 42L, CHAT_ID, MESSAGE_ID, null);
        ctx.attach(verdict);
        return ctx;
    }
}

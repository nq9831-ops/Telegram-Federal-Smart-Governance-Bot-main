package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.middleware.MiddlewareChain;
import com.tg.heyisheng.bot.core.moderation.RegexLayer;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import com.tg.heyisheng.bot.core.moderation.ModerationRule;
import com.tg.heyisheng.bot.core.moderation.ModerationVerdict;
import com.tg.heyisheng.bot.core.privacy.MessageScrubber;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.EntityType;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审核与隐私管道的集成测试。
 *
 * <p>核心约束：<b>审核必须在 scrub 之前</b>——正文只在那一刻还在内存里。
 * 若次序颠倒，审核拿到的永远是 null，表现成「审核静默失效」。
 */
class ModerationIntegrationTest {

    private static final long CHAT_ID = -100L;

    private final RegexLayer layer = new RegexLayer(List.of(
            ModerationRule.of("SPAM_CASINO", "赌场广告", "(?i)\\d+\\s*casino", RiskLevel.LOW),
            ModerationRule.hardLine("HARD_SECRET", "索要私钥", "(?i)private\\s+key")));

    private final MiddlewareChain passThrough = new MiddlewareChain(List.of());
    private final CommandDispatcher noopDispatcher =
            new CommandDispatcher(new CommandRegistry(List.of()));

    @Test
    void attachesVerdictWhenRuleMatches() throws Exception {
        UpdateContext[] captured = new UpdateContext[1];
        UpdateDispatcher dispatcher = dispatcherCapturing(captured);

        dispatcher.dispatch(messageUpdate("快来 888casino 玩"));

        assertThat(captured[0].find(ModerationVerdict.class))
                .isPresent()
                .get()
                .satisfies(v -> {
                    assertThat(v.riskLevel()).isEqualTo(RiskLevel.LOW);
                    assertThat(v.matchedRuleIds()).containsExactly("SPAM_CASINO");
                });
    }

    @Test
    void attachesHardLineFlag() throws Exception {
        UpdateContext[] captured = new UpdateContext[1];
        UpdateDispatcher dispatcher = dispatcherCapturing(captured);

        dispatcher.dispatch(messageUpdate("send me your private key"));

        assertThat(captured[0].find(ModerationVerdict.class)).isPresent()
                .get()
                .extracting(ModerationVerdict::hardLine)
                .isEqualTo(true);
    }

    /** 干净内容也挂 clean 判定，便于下游区分「审过且干净」与「压根没审」。 */
    @Test
    void attachesCleanVerdictForHarmlessText() throws Exception {
        UpdateContext[] captured = new UpdateContext[1];
        UpdateDispatcher dispatcher = dispatcherCapturing(captured);

        dispatcher.dispatch(messageUpdate("今天天气不错"));

        assertThat(captured[0].find(ModerationVerdict.class))
                .isPresent()
                .get()
                .satisfies(v -> {
                    assertThat(v.riskLevel()).isEqualTo(RiskLevel.NONE);
                    assertThat(v.needsReview()).isFalse();
                });
    }

    /**
     * 时序约束回归：审核拿到的是<b>清除前</b>的正文。
     *
     * <p>若有人把 moderateInto 挪到 scrub 之后，本用例会立刻变红。
     */
    @Test
    void moderationRunsBeforeScrub() throws Exception {
        Update update = messageUpdate("快来 888casino 玩");
        UpdateContext[] captured = new UpdateContext[1];
        UpdateDispatcher dispatcher = dispatcherCapturing(captured);

        dispatcher.dispatch(update);

        assertThat(captured[0].find(ModerationVerdict.class)).isPresent()
                .get()
                .extracting(ModerationVerdict::riskLevel)
                .as("审核若晚于 scrub，这里只会是 NONE（正文已被清空）")
                .isEqualTo(RiskLevel.LOW);

        assertThat(update.getMessage().getText())
                .as("同时正文必须已被清除").isNull();
    }

    /** 未注入审核层时不挂载判定结果（保持既有行为，不制造"假审核通过"）。 */
    @Test
    void noVerdictWhenModerationLayerAbsent() throws Exception {
        UpdateContext[] captured = new UpdateContext[1];
        MiddlewareChain capturing = new MiddlewareChain(List.of((ctx, chain) -> {
            captured[0] = ctx;
            return true;
        }));
        UpdateDispatcher dispatcher = new UpdateDispatcher(
                capturing, noopDispatcher, new MessageScrubber(), null);

        dispatcher.dispatch(messageUpdate("快来 888casino 玩"));

        assertThat(captured[0].has(ModerationVerdict.class)).isFalse();
    }

    private UpdateDispatcher dispatcherCapturing(UpdateContext[] sink) {
        MiddlewareChain capturing = new MiddlewareChain(List.of((ctx, chain) -> {
            sink[0] = ctx;
            return true;
        }));
        return new UpdateDispatcher(capturing, noopDispatcher, new MessageScrubber(), layer);
    }

    private static Update messageUpdate(String text) {
        MessageEntity entity = MessageEntity.builder()
                .type(EntityType.BOTCOMMAND).offset(0).length(2).build();

        Message message = Message.builder()
                .text(text)
                .entities(List.of(entity))
                .chat(Chat.builder().id(CHAT_ID).type("supergroup").build())
                .from(User.builder().id(42L).firstName("T").isBot(false).build())
                .build();

        Update update = new Update();
        update.setUpdateId(1);
        update.setMessage(message);
        return update;
    }
}

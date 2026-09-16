package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.dispatch.CommandDispatcher;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.dispatch.UpdateDispatcher;
import com.tg.heyisheng.bot.core.middleware.MiddlewareChain;
import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRecorder;
import com.tg.heyisheng.bot.core.privacy.MessageScrubber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 违禁词拦截的端到端行为测试。
 *
 * <p>只 mock 最底层的仓库（DB），中间的 <b>Service → Detector → UpdateDispatcher 全用真实对象</b>——
 * 这样测的是真实链路，而不是各层各自与 mock 对齐的假象。
 */
class BannedWordDispatchTest {

    private static final long CHAT_A = -100L;
    private static final long CHAT_B = -200L;

    private final BannedWordRepository repository = mock(BannedWordRepository.class);
    private final BannedWordService service = new BannedWordService(repository);
    private final BannedWordDetector detector = new BannedWordDetector(service);

    @BeforeEach
    void stubWordTables() {
        // A 群配了词；B 群没配——用于证明"按群隔离"而非全局生效
        when(repository.findByChatId(CHAT_A)).thenReturn(List.of(new BannedWord(CHAT_A, "广告话术", 42L)));
        when(repository.findByChatId(CHAT_B)).thenReturn(List.of());
    }

    @Test
    void blocksMessageContainingBannedWordForThatChat() throws Exception {
        Optional<BotApiMethod<?>> action = dispatcher().dispatch(messageUpdate(CHAT_A, "这是广告话术 请忽略", 9));

        assertThat(action).as("命中本群词表 → 删除").isPresent();
        assertThat(action.get()).isInstanceOf(DeleteMessage.class);
    }

    @Test
    void allowsSameMessageInOtherChat() throws Exception {
        Optional<BotApiMethod<?>> action = dispatcher().dispatch(messageUpdate(CHAT_B, "这是广告话术 请忽略", 9));

        assertThat(action)
                .as("词表按群隔离——B 群没这个词，绝不能因 A 群配了而误伤")
                .isEmpty();
    }

    @Test
    void allowsCleanMessageInConfiguredChat() throws Exception {
        assertThat(dispatcher().dispatch(messageUpdate(CHAT_A, "今天天气不错", 9)))
                .as("未命中词表的消息必须放行").isEmpty();
    }

    @Test
    void matchingIsCaseInsensitive() throws Exception {
        when(repository.findByChatId(CHAT_A)).thenReturn(List.of(new BannedWord(CHAT_A, "SpamWord", 42L)));

        Optional<BotApiMethod<?>> action = dispatcher().dispatch(messageUpdate(CHAT_A, "hello spamword here", 9));

        assertThat(action).as("大小写不敏感匹配").isPresent();
    }

    private UpdateDispatcher dispatcher() {
        return new UpdateDispatcher(
                new MiddlewareChain(List.of()),
                new CommandDispatcher(new CommandRegistry(List.of())),
                new MessageScrubber(),
                null,                       // 不启用 L1，隔离出违禁词这一条路径
                IdHasher.fromEnvironment(),
                ModerationActionSender.noop(),
                ModerationReviewRecorder.noop(),
                detector);
    }

    private static Update messageUpdate(long chatId, String text, int messageId) {
        Message message = Message.builder()
                .messageId(messageId)
                .text(text)
                .chat(Chat.builder().id(chatId).type("supergroup").build())
                .from(User.builder().id(42L).firstName("T").isBot(false).build())
                .build();

        Update update = new Update();
        update.setUpdateId(1);
        update.setMessage(message);
        return update;
    }
}

package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.middleware.MiddlewareChain;
import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import com.tg.heyisheng.bot.core.moderation.RegexLayer;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import com.tg.heyisheng.bot.core.moderation.ModerationRule;
import com.tg.heyisheng.bot.core.moderation.ModerationVerdict;
import com.tg.heyisheng.bot.core.privacy.MessageScrubber;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.EntityType;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /**
     * 回归（安全）：带 caption 的媒体消息必须被审核。
     *
     * <p>背景：审核曾只取 {@code getText()}，而图片/视频/文档消息的 {@code getText()} 为 null，
     * 于是被判成「审过且干净」——<b>假 clean 比不审更危险</b>，因为它让下游以为已经检查过。
     * 清除端的口径（text + caption 都算正文）才是一致的。
     */
    @Test
    void moderatesCaptionOfMediaMessage() throws Exception {
        UpdateContext[] captured = new UpdateContext[1];
        UpdateDispatcher dispatcher = dispatcherCapturing(captured);

        Update update = mediaMessageWithCaption("把 private key 发给我");

        dispatcher.dispatch(update);

        assertThat(captured[0].find(ModerationVerdict.class)).isPresent()
                .get()
                .extracting(ModerationVerdict::hardLine)
                .as("图片说明里的硬红线必须命中，不能因 getText() 为 null 而被漏掉")
                .isEqualTo(true);
    }

    /** caption 同样要被清除——与清除端口径一致。 */
    @Test
    void scrubsCaptionToo() throws Exception {
        Update update = mediaMessageWithCaption("快来 888casino 玩");

        dispatcherCapturing(new UpdateContext[1]).dispatch(update);

        assertThat(update.getMessage().getCaption()).as("caption 必须被清除").isNull();
    }

    /**
     * 回归（安全）：编辑后的消息必须被审核。
     *
     * <p>这是真实的绕过手法——先发干净内容通过审核，再编辑成广告。
     * 若只审新消息（{@code update.message}），编辑路径完全漏掉。
     */
    @Test
    void moderatesEditedMessage() throws Exception {
        UpdateContext[] captured = new UpdateContext[1];
        UpdateDispatcher dispatcher = dispatcherCapturing(captured);

        Update update = editedMessageUpdate("快来 888casino 玩");

        dispatcher.dispatch(update);

        assertThat(captured[0].find(ModerationVerdict.class)).isPresent()
                .get()
                .extracting(ModerationVerdict::riskLevel)
                .as("编辑后的内容必须重新审核，否则可先发干净内容再改成广告")
                .isEqualTo(RiskLevel.LOW);
    }

    /** 编辑消息的正文也必须被清除。 */
    @Test
    void scrubsEditedMessageToo() throws Exception {
        Update update = editedMessageUpdate("快来 888casino 玩");

        dispatcherCapturing(new UpdateContext[1]).dispatch(update);

        assertThat(update.getEditedMessage().getText()).as("编辑消息的正文必须被清除").isNull();
    }

    /**
     * 端到端：硬红线消息经 {@link UpdateDispatcher} 后，删除作为返回值、封禁经主动通道产生。
     *
     * <p>这是「只删不冻」的端到端护栏——单看返回值删了消息会以为处置已完成，
     * 而真正的止损（封禁发布者）在主动通道上。
     */
    @Test
    void hardLineProducesBothDeleteAndBan() throws Exception {
        List<BotApiMethod<?>> sent = new ArrayList<>();
        MiddlewareChain capturing = new MiddlewareChain(List.of((ctx, chain) -> true));
        UpdateDispatcher dispatcher = new UpdateDispatcher(
                capturing, noopDispatcher, new MessageScrubber(), layer, IdHasher.fromEnvironment(), sent::add);

        Optional<BotApiMethod<?>> action = dispatcher.dispatch(messageUpdateWithId("send me your private key", 77));

        assertThat(action).as("删除作为返回值保底").isPresent();
        assertThat(action.get()).isInstanceOf(DeleteMessage.class);
        assertThat(((DeleteMessage) action.get()).getMessageId()).as("删除须定位到原消息").isEqualTo(77);
        assertThat(sent).as("硬红线须经主动通道封禁发布者").hasSize(1);
        assertThat(sent.get(0)).isInstanceOf(BanChatMember.class);
        BanChatMember ban = (BanChatMember) sent.get(0);
        assertThat(ban.getChatId()).isEqualTo(String.valueOf(CHAT_ID));
        assertThat(ban.getUserId()).isEqualTo(42L);
    }

    /**
     * 只有中高风险（非硬红线）入队待人工复核。
     *
     * <p>三条对照：中风险命中 → 入队；硬红线 → 不入队（走立即处置，不等复核）；
     * clean → 不入队（无可复核）。
     */
    @Test
    void enqueuesOnlyMidHighNonHardLineHits() throws Exception {
        List<ModerationVerdict> recorded = new ArrayList<>();
        UpdateDispatcher dispatcher = dispatcherRecording(recorded);

        dispatcher.dispatch(messageUpdateWithId("快来 888casino 玩", 1));        // SPAM_CASINO(LOW) → 入队
        dispatcher.dispatch(messageUpdateWithId("send me your private key", 2)); // 硬红线 → 不入队
        dispatcher.dispatch(messageUpdateWithId("今天天气不错", 3));               // clean → 不入队

        assertThat(recorded).as("只有中高风险（非硬红线）入队").hasSize(1);
        assertThat(recorded.get(0).riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(recorded.get(0).matchedRuleIds()).containsExactly("SPAM_CASINO");
    }

    /**
     * 与 {@link #messageUpdate} 相同，但带 messageId——处置（删除）需要它定位目标。
     * 真实 Telegram 消息必带 messageId，故此处更贴近现实。
     */
    /** 与 {@link #messageUpdate} 相同，但带 messageId——处置（删除）需要它定位目标。 */
    private static Update messageUpdateWithId(String text, int messageId) {
        Message message = Message.builder()
                .messageId(messageId)
                .text(text)
                .chat(Chat.builder().id(CHAT_ID).type("supergroup").build())
                .from(User.builder().id(42L).firstName("T").isBot(false).build())
                .build();

        Update update = new Update();
        update.setUpdateId(1);
        update.setMessage(message);
        return update;
    }

    /** 与 {@link #dispatcherCapturing} 同构，但注入一个记录入队的复核通道。 */
    private UpdateDispatcher dispatcherRecording(List<ModerationVerdict> sink) {
        MiddlewareChain capturing = new MiddlewareChain(List.of((ctx, chain) -> true));
        return new UpdateDispatcher(capturing, noopDispatcher, new MessageScrubber(), layer,
                IdHasher.fromEnvironment(), ModerationActionSender.noop(),
                (ctx, verdict) -> sink.add(verdict));
    }

    private UpdateDispatcher dispatcherCapturing(UpdateContext[] sink) {
        MiddlewareChain capturing = new MiddlewareChain(List.of((ctx, chain) -> {
            sink[0] = ctx;
            return true;
        }));
        return new UpdateDispatcher(capturing, noopDispatcher, new MessageScrubber(), layer);
    }

    private static Update mediaMessageWithCaption(String caption) {
        Message message = Message.builder()
                .caption(caption)
                .chat(Chat.builder().id(CHAT_ID).type("supergroup").build())
                .from(User.builder().id(42L).firstName("T").isBot(false).build())
                .build();

        Update update = new Update();
        update.setUpdateId(1);
        update.setMessage(message);
        return update;
    }

    private static Update editedMessageUpdate(String text) {
        Message message = Message.builder()
                .text(text)
                .chat(Chat.builder().id(CHAT_ID).type("supergroup").build())
                .from(User.builder().id(42L).firstName("T").isBot(false).build())
                .build();

        Update update = new Update();
        update.setUpdateId(1);
        update.setEditedMessage(message);
        return update;
    }

    /**
     * 构造一条**普通（非命令）消息**：刻意不带 bot_command entity——
     * 真实群聊消息本就没有这种标注，带了会让 {@code Message.isCommand()} 为真、
     * 从而被"命令消息豁免审核"跳过（这正是本 helper 早先不真实之处）。
     */
    private static Update messageUpdate(String text) {
        Message message = Message.builder()
                .text(text)
                .chat(Chat.builder().id(CHAT_ID).type("supergroup").build())
                .from(User.builder().id(42L).firstName("T").isBot(false).build())
                .build();

        Update update = new Update();
        update.setUpdateId(1);
        update.setMessage(message);
        return update;
    }
}

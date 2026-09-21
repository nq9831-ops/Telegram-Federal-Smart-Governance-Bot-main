package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.dispatch.CommandDispatcher;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.dispatch.EchoCommandHandler;
import com.tg.heyisheng.bot.core.dispatch.UpdateDispatcher;
import com.tg.heyisheng.bot.core.middleware.MiddlewareChain;
import com.tg.heyisheng.bot.core.moderation.CaseAppealCommandHandler;
import com.tg.heyisheng.bot.core.moderation.CaseAppealRepository;
import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRecorder;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.Role;
import com.tg.heyisheng.bot.core.privacy.MessageScrubber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
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

    /** 放行一切权限（模拟已授权管理员）。 */
    private static final PermissionChecker ALLOW_ALL = new PermissionChecker(Role.OWNER);
    /** 拒绝一切受限权限（模拟普通成员）——用于验证"命令外壳"不能成为绕过审核的通道。 */
    private static final PermissionChecker DENY_ALL = new PermissionChecker(Role.MEMBER);

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

    /**
     * 命令消息必须豁免内容审核——它是**控制面**（管理操作），不是群聊内容。
     *
     * <p>回归背景（P0）：`/delword <词>` 的命令文本里含该词本身，若照常送审，
     * 会先被判违禁而删除、`dispatch` 提前返回，命令永不执行——`/delword` 对它唯一的
     * 用途（删掉词表里已有的词）100% 失效。
     */
    @Test
    void commandMessageIsNotBlockedByModeration() throws Exception {
        Optional<BotApiMethod<?>> action = dispatcherWithCommands()
                .dispatch(commandUpdate("/delword 广告话术", 8));

        assertThat(action).as("命令消息不得被内容审核拦掉").isPresent();
        assertThat(action.get())
                .as("应当执行命令（回复），而不是被当作违规消息删除")
                .isInstanceOf(SendMessage.class);
    }

    /** 回归：豁免不能过宽——普通（非命令）消息命中词表时仍必须删除。 */
    @Test
    void plainMessageWithSameTextIsStillBlocked() throws Exception {
        Optional<BotApiMethod<?>> action = dispatcherWithCommands()
                .dispatch(messageUpdate(CHAT_A, "广告话术", 10));

        assertThat(action).isPresent();
        assertThat(action.get()).isInstanceOf(DeleteMessage.class);
    }

    private UpdateDispatcher dispatcher() {
        return UpdateDispatcher.builder()
                .middlewareChain(new MiddlewareChain(List.of()))
                .commandDispatcher(new CommandDispatcher(new CommandRegistry(List.of())))
                .scrubber(new MessageScrubber())
                // 不启用 L1（moderationLayer 留空），隔离出违禁词这一条路径
                .idHasher(IdHasher.fromEnvironment())
                .actionSender(ModerationActionSender.noop())
                .reviewRecorder(ModerationReviewRecorder.noop())
                .bannedWordDetector(detector)
                .build();
    }

    /**
     * 绕过回归（HIGH）：**无权限**成员把违规内容写成命令外壳，不得被豁免审核。
     *
     * <p>豁免条件必须是"这条消息会**真的执行**一条已授权命令"。若只看 {@code isCommand()}，
     * 任何人加个 {@code /xxx } 前缀就能让违规内容免于删除——消息不被删、
     * 命令又因权限不足而不执行，内容就留在群里了。
     */
    @Test
    void unauthorizedCommandShellDoesNotBypassModeration() throws Exception {
        Optional<BotApiMethod<?>> action = dispatcherWith(DENY_ALL)
                .dispatch(commandUpdate("/delword 广告话术", 8));

        assertThat(action).as("无权限者的命令外壳不得豁免审核").isPresent();
        assertThat(action.get()).as("违规内容仍须被删除").isInstanceOf(DeleteMessage.class);
    }

    /** 绕过回归：**未注册**的命令外壳同样不得豁免。 */
    @Test
    void unregisteredCommandShellDoesNotBypassModeration() throws Exception {
        Optional<BotApiMethod<?>> action = dispatcherWithCommands()
                .dispatch(commandUpdate("/nonsense 广告话术", 9));

        assertThat(action).as("未注册命令的外壳不得豁免审核").isPresent();
        assertThat(action.get()).as("违规内容仍须被删除").isInstanceOf(DeleteMessage.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 公开命令的豁免边界（豁免 = 有权限要求的命令 ∪ 白名单）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ★ 回归：**公开命令且不在白名单**的文本仍须送审。
     *
     * <p>豁免的初衷是「会被执行的管理命令属控制面」——但 {@code /echo} 是**任何成员都能用**的
     * 自助命令，它的参数会被原样留在群里，不属于控制面。若一并豁免，任何成员发
     * {@code /echo <违规内容>} 即可让违规正文**免于删除**（命令照常执行、消息留在群内且无审核记录）。
     */
    @Test
    void publicCommandOutsideAllowlistIsStillModerated() throws Exception {
        Optional<BotApiMethod<?>> action = dispatcherWithPublicEcho()
                .dispatch(commandUpdate("/echo 广告话术", 5));

        assertThat(action).as("公开命令的文本同样要过内容审核").isPresent();
        assertThat(action.get()).as("命中本群词表 → 仍须删除").isInstanceOf(DeleteMessage.class);
    }

    /**
     * 白名单内的公开命令仍**豁免**：{@code /case_appeal} 的参数是用户手写的申诉正文，
     * 若送审，理由里一旦出现违禁词就会把**申诉本身**删掉——当事人永远申诉不了。
     */
    @Test
    void allowlistedPublicCommandStaysExempt() throws Exception {
        Optional<BotApiMethod<?>> action = dispatcherWithCaseAppeal()
                .dispatch(commandUpdate("/case_appeal 7 广告话术是我引用的原文", 12));

        assertThat(action).as("白名单公开命令仍豁免审核").isPresent();
        assertThat(action.get())
                .as("应当执行命令（回执），而不是被当作违规消息删除")
                .isInstanceOf(SendMessage.class);
    }

    /** 装配：注册真实 {@code /echo}（publicCommand=true、无权限点）——即本回归要挡的那条路径。 */
    private UpdateDispatcher dispatcherWithPublicEcho() {
        return withCommandHandler(new EchoCommandHandler());
    }

    /** 装配：注册真实 {@code /case_appeal}（publicCommand=true、在白名单内）。 */
    private UpdateDispatcher dispatcherWithCaseAppeal() {
        return withCommandHandler(new CaseAppealCommandHandler(
                mock(ModerationReviewRepository.class), mock(CaseAppealRepository.class)));
    }

    private UpdateDispatcher withCommandHandler(CommandHandler handler) {
        return UpdateDispatcher.builder()
                .middlewareChain(new MiddlewareChain(List.of()))
                .commandDispatcher(new CommandDispatcher(
                        new CommandRegistry(List.of(handler)), ALLOW_ALL))
                .scrubber(new MessageScrubber())
                .idHasher(IdHasher.fromEnvironment())
                .actionSender(ModerationActionSender.noop())
                .reviewRecorder(ModerationReviewRecorder.noop())
                .bannedWordDetector(detector)
                .build();
    }

    /** 带真实 /delword 命令的 dispatcher，并放行权限——用于验证命令消息能否穿过审核链到达命令层。 */
    private UpdateDispatcher dispatcherWithCommands() {
        // /delword 需要 MANAGE_CONFIG，故此处放行权限——本测试聚焦"审核是否拦截命令"，不是权限门控
        return dispatcherWith(ALLOW_ALL);
    }

    /** 权限判定可注入：同一套装配，只换权限来源，才能隔离出"豁免条件是否正确"。 */
    private UpdateDispatcher dispatcherWith(PermissionChecker permissionChecker) {
        return UpdateDispatcher.builder()
                .middlewareChain(new MiddlewareChain(List.of()))
                .commandDispatcher(new CommandDispatcher(
                        new CommandRegistry(List.of(new DelWordCommandHandler(service))), permissionChecker))
                .scrubber(new MessageScrubber())
                .idHasher(IdHasher.fromEnvironment())
                .actionSender(ModerationActionSender.noop())
                .reviewRecorder(ModerationReviewRecorder.noop())
                .bannedWordDetector(detector)
                .build();
    }

    private static Update commandUpdate(String text, int commandLength) {
        Message message = Message.builder()
                .messageId(11)
                .text(text)
                .entities(List.of(org.telegram.telegrambots.meta.api.objects.MessageEntity.builder()
                        .type(org.telegram.telegrambots.meta.api.objects.EntityType.BOTCOMMAND)
                        .offset(0).length(commandLength).build()))
                .chat(Chat.builder().id(CHAT_A).type("supergroup").build())
                .from(User.builder().id(42L).firstName("Admin").isBot(false).build())
                .build();

        Update update = new Update();
        update.setUpdateId(2);
        update.setMessage(message);
        return update;
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

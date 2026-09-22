package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.middleware.AuthenticationMiddleware;
import com.tg.heyisheng.bot.core.middleware.MiddlewareChain;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.EntityType;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.polls.Poll;
import org.telegram.telegrambots.meta.api.objects.polls.PollOption;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UpdateDispatcher 测试：聚焦「库的 Update → 项目上下文」的提取，以及上下文向 handler 的<b>贯通</b>。
 *
 * <p>贯通是本次修复的重点：此前中间件链与 handler 各持一个上下文对象，
 * 中间件的任何 enrich 都传不到 handler。
 */
class UpdateDispatcherTest {

    /** GUARD-4 update_id 去重后每次 dispatch 需唯一 id——本类独立计数基数 21000（防跨类碰撞）。 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger(21000);

    private final CommandRegistry registry = new CommandRegistry(List.of(new EchoCommandHandler()));

    @Test
    void extractsRoutingMetadataFromUpdate() throws Exception {
        UpdateContext[] seenByMiddleware = new UpdateContext[1];
        UpdateDispatcher dispatcher = dispatcherCapturing(seenByMiddleware);

        Update dispatched = update("/echo", 42L, -100L);
        dispatcher.dispatch(dispatched);

        UpdateContext ctx = seenByMiddleware[0];
        assertThat(ctx.updateId()).isEqualTo(dispatched.getUpdateId());
        assertThat(ctx.userId()).isEqualTo(42L);
        assertThat(ctx.chatId()).isEqualTo(-100L);
        assertThat(ctx.command()).contains("/echo");
    }

    /** 关键回归：中间件看到的对象与 handler 收到的必须是同一个（避免 enrich 丢失）。 */
    @Test
    void sameContextInstanceReachesHandler() throws Exception {
        UpdateContext[] seenByMiddleware = new UpdateContext[1];
        UpdateContext[] seenByHandler = new UpdateContext[1];

        CommandHandler capturingHandler = ctx -> {
            seenByHandler[0] = ctx;
            return null;
        };
        CommandRegistry capturingRegistry = new CommandRegistry(List.of(new CapturingBean(capturingHandler)));
        MiddlewareChain chain = new MiddlewareChain(List.of((ctx, c) -> {
            seenByMiddleware[0] = ctx;
            return true;
        }));

        dispatcher(chain, new CommandDispatcher(capturingRegistry))
                .dispatch(update("/capture", 42L, -100L));

        assertThat(seenByHandler[0]).as("handler 必须收到中间件链上那个上下文").isSameAs(seenByMiddleware[0]);
    }

    @Test
    void middlewareInterruptionSkipsCommandDispatch() throws Exception {
        UpdateDispatcher dispatcher = dispatcher(
                new MiddlewareChain(List.of(new AuthenticationMiddleware())),
                new CommandDispatcher(registry));

        // 缺 from → userId 为 null → 链首认证中间件中断
        assertThat(dispatcher.dispatch(update("/echo", null, -100L))).isEmpty();
    }

    /** 隐私管道：正常处理完也要清除正文。 */
    @Test
    void scrubsMessageTextAfterSuccessfulDispatch() throws Exception {
        Update update = update("/echo", 42L, -100L);

        dispatcher(new MiddlewareChain(List.of()), new CommandDispatcher(registry))
                .dispatch(update);

        assertThat(update.getMessage().getText()).as("处理后不得残留消息原文").isNull();
    }

    /** 隐私管道：被中断的路径同样要清除——它一样会走到日志与审计。 */
    @Test
    void scrubsMessageTextWhenChainInterrupted() throws Exception {
        Update update = update("/echo", null, -100L);

        dispatcher(new MiddlewareChain(List.of(new AuthenticationMiddleware())),
                new CommandDispatcher(registry))
                .dispatch(update);

        assertThat(update.getMessage().getText()).as("中断路径也不得残留消息原文").isNull();
    }

    /** 命令操作数提取：命令名之后的全部文本。 */
    @Test
    void extractCommandArgsReturnsTextAfterFirstWhitespace() {
        Message message = Message.builder()
                .messageId(1)
                .text("/addword 广告 话术")
                .entities(List.of(MessageEntity.builder()
                        .type(EntityType.BOTCOMMAND).offset(0).length(8).build()))
                .build();

        assertThat(UpdateDispatcher.extractCommandArgs(message)).isEqualTo("广告 话术");
    }

    /** 带 {@code @BotName} 后缀的命令同样正确剥离。 */
    @Test
    void extractCommandArgsHandlesBotNameSuffix() {
        Message message = Message.builder()
                .messageId(1)
                .text("/addword@MyBot 广告")
                .entities(List.of(MessageEntity.builder()
                        .type(EntityType.BOTCOMMAND).offset(0).length(14).build()))
                .build();

        assertThat(UpdateDispatcher.extractCommandArgs(message)).isEqualTo("广告");
    }

    /** 非命令消息不得产出 args——否则它就成了「消息正文」的侧路，破坏隐私约束。 */
    @Test
    void extractCommandArgsIsNullForNonCommandMessage() {
        Message message = Message.builder().messageId(1).text("这是普通聊天内容").build();

        assertThat(UpdateDispatcher.extractCommandArgs(message)).isNull();
    }

    /** 只有命令名、没有参数时为空。 */
    @Test
    void extractCommandArgsIsNullWhenNoArgs() {
        Message message = Message.builder()
                .messageId(1)
                .text("/echo")
                .entities(List.of(MessageEntity.builder()
                        .type(EntityType.BOTCOMMAND).offset(0).length(5).build()))
                .build();

        assertThat(UpdateDispatcher.extractCommandArgs(message)).isNull();
    }

    /**
     * 审核口径必须覆盖投票文本——否则投票问题/选项里的违规内容
     * 既不被审核（假 clean）也不被清除（隐私面）。
     */
    @Test
    void contentOfIncludesPollText() {
        Message message = Message.builder()
                .poll(Poll.builder()
                        .question("快来 888casino 玩")
                        .options(List.of(PollOption.builder().text("正常选项").build()))
                        .build())
                .build();

        assertThat(UpdateDispatcher.contentOf(message)).contains("888casino");
    }

    @BotCommand("capture")
    static class CapturingBean implements CommandHandler {
        private final CommandHandler delegate;

        CapturingBean(CommandHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod<?> handle(UpdateContext ctx)
                throws Exception {
            return delegate.handle(ctx);
        }
    }

    /** 装配入口统一走 builder——生产侧已不提供构造重载（见 {@code UpdateDispatcher#builder}）。 */
    private static UpdateDispatcher dispatcher(MiddlewareChain chain, CommandDispatcher commands) {
        return UpdateDispatcher.builder().middlewareChain(chain).commandDispatcher(commands).build();
    }

    private UpdateDispatcher dispatcherCapturing(UpdateContext[] sink) {
        MiddlewareChain chain = new MiddlewareChain(List.of((ctx, c) -> {
            sink[0] = ctx;
            return true;
        }));
        return dispatcher(chain, new CommandDispatcher(registry));
    }

    private static Update update(String commandText, Long fromId, Long chatId) {
        MessageEntity entity = MessageEntity.builder()
                .type(EntityType.BOTCOMMAND)
                .offset(0)
                .length(commandText.length())
                .build();

        var messageBuilder = Message.builder()
                .text(commandText)
                .entities(List.of(entity));

        if (chatId != null) {
            messageBuilder.chat(Chat.builder().id(chatId).type("supergroup").build());
        }
        if (fromId != null) {
            // User 的 firstName / isBot 均被 @NonNull 标注，构造时必须提供
            messageBuilder.from(User.builder()
                    .id(fromId)
                    .firstName("Test")
                    .isBot(false)
                    .build());
        }

        Update update = new Update();
        update.setUpdateId(SEQ.incrementAndGet());
        update.setMessage(messageBuilder.build());
        return update;
    }
}

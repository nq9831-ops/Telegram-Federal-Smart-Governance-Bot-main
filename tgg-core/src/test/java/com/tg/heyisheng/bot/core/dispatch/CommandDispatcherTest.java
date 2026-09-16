package com.tg.heyisheng.bot.core.dispatch;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.EntityType;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 命令分发测试。
 *
 * <p>夹具按 Telegram 的真实结构构造：命令由 <b>MessageEntity(bot_command)</b> 标注，
 * 而非"文本以斜杠开头"——这正是 {@code Message.getCommand()} 的依据。
 *
 * <p>对象构造一律走 TelegramBots 的 builder（这些类的无参构造器是 protected 或不存在，
 * 直接 {@code new} 会编译失败）。
 */
class CommandDispatcherTest {

    private static final long CHAT_ID = -100L;

    private final CommandRegistry registry = new CommandRegistry(List.of(new EchoCommandHandler()));
    private final CommandDispatcher dispatcher = new CommandDispatcher(registry);

    @Test
    void routesKnownCommandAndProducesReply() throws Exception {
        Optional<BotApiMethod<?>> reply = dispatcher.dispatch(commandMessage("/echo"));

        assertThat(reply).isPresent();
        assertThat(reply.get()).isInstanceOf(SendMessage.class);
        SendMessage send = (SendMessage) reply.get();
        assertThat(send.getText()).isEqualTo(EchoCommandHandler.REPLY_TEXT);
        assertThat(send.getChatId()).isEqualTo(String.valueOf(CHAT_ID));
    }

    @Test
    void routesCommandWithBotUsernameSuffix() throws Exception {
        Optional<BotApiMethod<?>> reply = dispatcher.dispatch(commandMessage("/echo@MyGovernanceBot"));

        assertThat(reply).as("/cmd@BotName 形式必须能路由").isPresent();
    }

    @Test
    void routesAlias() throws Exception {
        assertThat(dispatcher.dispatch(commandMessage("/ping"))).isPresent();
    }

    @Test
    void unknownCommandYieldsEmpty() throws Exception {
        assertThat(dispatcher.dispatch(commandMessage("/unknown"))).isEmpty();
    }

    @Test
    void nonCommandMessageYieldsEmpty() throws Exception {
        Message message = Message.builder().text("just chatting").chat(chat()).build();

        Update update = new Update();
        update.setUpdateId(1);
        update.setMessage(message);

        assertThat(dispatcher.dispatch(update)).isEmpty();
    }

    @Test
    void updateWithoutMessageYieldsEmpty() throws Exception {
        Update update = new Update();
        update.setUpdateId(1);

        assertThat(dispatcher.dispatch(update)).isEmpty();
    }

    private static Update commandMessage(String commandText) {
        MessageEntity commandEntity = MessageEntity.builder()
                .type(EntityType.BOTCOMMAND)
                .offset(0)
                .length(commandText.length())
                .build();

        Message message = Message.builder()
                .text(commandText)
                .entities(List.of(commandEntity))
                .chat(chat())
                .build();

        Update update = new Update();
        update.setUpdateId(1);
        update.setMessage(message);
        return update;
    }

    private static Chat chat() {
        return Chat.builder().id(CHAT_ID).type("supergroup").build();
    }
}

package com.tg.heyisheng.bot.core.privacy;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.EntityType;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 隐私管道测试。
 *
 * <p>对应 V5.0 硬约束：「丢弃消息原文 —— content = null，内存清理」。
 * 切片 1 已让 {@code UpdateContext} 不持有正文（结构性预防），
 * 本类负责把库对象里**已经存在**的正文主动清除。
 */
class MessageScrubberTest {

    private final MessageScrubber scrubber = new MessageScrubber();

    @Test
    void clearsTextAndCaption() {
        Message message = Message.builder()
                .text("这是一段需要被丢弃的消息原文")
                .caption("附件说明")
                .build();

        scrubber.scrub(message);

        assertThat(message.getText()).isNull();
        assertThat(message.getCaption()).isNull();
        assertThat(message.hasText()).isFalse();
    }

    @Test
    void clearsEntitiesAlongWithText() {
        MessageEntity entity = MessageEntity.builder()
                .type(EntityType.BOTCOMMAND)
                .offset(0)
                .length(5)
                .build();
        Message message = Message.builder().text("/echo hello").entities(List.of(entity)).build();

        scrubber.scrub(message);

        assertThat(message.getText()).isNull();
        assertThat(message.getEntities()).isNull();
    }

    /** 路由元数据必须保留——否则审计追溯与后续业务（如按群配置）都会断。 */
    @Test
    void preservesRoutingMetadata() {
        Message message = Message.builder()
                .text("敏感内容")
                .messageId(10)
                .chat(Chat.builder().id(-100L).type("supergroup").build())
                .from(User.builder().id(42L).firstName("T").isBot(false).build())
                .build();

        scrubber.scrub(message);

        assertThat(message.getMessageId()).isEqualTo(10);
        assertThat(message.getChat()).isNotNull();
        assertThat(message.getChat().getId()).isEqualTo(-100L);
        assertThat(message.getFrom()).isNotNull();
        assertThat(message.getFrom().getId()).isEqualTo(42L);
    }

    @Test
    void toleratesNullMessage() {
        assertThatCode(() -> scrubber.scrub(null)).doesNotThrowAnyException();
    }

    @Test
    void toleratesMessageWithoutText() {
        assertThatCode(() -> scrubber.scrub(Message.builder().messageId(1).build()))
                .doesNotThrowAnyException();
    }
}

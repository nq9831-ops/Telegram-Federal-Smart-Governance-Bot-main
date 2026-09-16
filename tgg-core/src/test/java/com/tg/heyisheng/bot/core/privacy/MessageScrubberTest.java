package com.tg.heyisheng.bot.core.privacy;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.EntityType;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.Venue;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.polls.Poll;
import org.telegram.telegrambots.meta.api.objects.polls.PollOption;
import org.telegram.telegrambots.meta.api.objects.polls.PollOptionAdded;
import org.telegram.telegrambots.meta.api.objects.polls.PollOptionDeleted;
import org.telegram.telegrambots.meta.api.objects.stories.Story;

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

    /**
     * 投票、地点、故事、链接预览都承载用户文本，必须一并清除——测试须覆盖到每一个新增的清除点，
     * 否则"改了实现却没测"（本用例初版即犯此错：名字提了 story 却只构造 poll/venue）。
     *
     * <p>回归背景：初版只清 text/caption/entities 等六个字段，这些被漏掉——
     * 它们既不进审核（假 clean）也不被清除（泄露面）。
     */
    @Test
    void clearsPollVenueStoryAndLinkPreview() {
        Message message = Message.builder()
                .poll(Poll.builder()
                        .question("投票问题")
                        .options(List.of(PollOption.builder().text("选项一").build()))
                        .build())
                .venue(Venue.builder().title("某地点").address("某地址").build())
                .pollOptionAdded(PollOptionAdded.builder().optionText("新增选项").build())
                .pollOptionDeleted(PollOptionDeleted.builder().optionText("删除选项").build())
                .story(Story.builder().build())
                .replyToStory(Story.builder().build())
                .linkPreviewOptions(LinkPreviewOptions.builder().urlField("https://example.com").build())
                .build();

        scrubber.scrub(message);

        assertThat(message.getPoll()).as("投票问题与选项都是用户文本").isNull();
        assertThat(message.getVenue()).as("地点标题与地址是用户文本").isNull();
        assertThat(message.getPollOptionAdded()).as("选项增删事件带 optionText").isNull();
        assertThat(message.getPollOptionDeleted()).isNull();
        assertThat(message.getStory()).isNull();
        assertThat(message.getReplyToStory()).isNull();
        assertThat(message.getLinkPreviewOptions()).as("含用户可控的 url").isNull();
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

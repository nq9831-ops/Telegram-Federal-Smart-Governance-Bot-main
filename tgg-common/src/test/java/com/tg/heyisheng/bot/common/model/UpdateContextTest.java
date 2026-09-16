package com.tg.heyisheng.bot.common.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UpdateContext 测试。
 *
 * <p>重点不是 getter 是否工作，而是<b>结构性隐私约束</b>：上下文不得携带消息正文。
 */
class UpdateContextTest {

    /**
     * 切片 3「消息原文零存储」的前置约束：UpdateContext 不得有任何承载正文的字段。
     * 这条断言会在有人日后加回正文字段时立即变红。
     */
    @Test
    void mustNotDeclareMessageTextFields() {
        List<String> fieldNames = Arrays.stream(UpdateContext.class.getDeclaredFields())
                .map(Field::getName)
                .toList();

        assertThat(fieldNames)
                .doesNotContain("text", "content", "messageText", "rawText", "message", "caption", "body");
    }

    @Test
    void holdsRoutingMetadata() {
        UpdateContext ctx = new UpdateContext(1, 100L, -200L, "/echo");

        assertThat(ctx.updateId()).isEqualTo(1);
        assertThat(ctx.userId()).isEqualTo(100L);
        assertThat(ctx.chatId()).isEqualTo(-200L);
        assertThat(ctx.command()).contains("/echo");
        assertThat(ctx.hasCommand()).isTrue();
    }

    @Test
    void commandIsEmptyWhenAbsent() {
        UpdateContext ctx = new UpdateContext(1, 100L, -200L, null);

        assertThat(ctx.command()).isEmpty();
        assertThat(ctx.hasCommand()).isFalse();
    }

    @Test
    void attachesAndFindsEnrichedDataByType() {
        UpdateContext ctx = new UpdateContext(1, 100L, -200L, "/echo");
        String enriched = "middleware-enriched-data";

        ctx.attach(enriched);

        assertThat(ctx.has(String.class)).isTrue();
        assertThat(ctx.find(String.class)).contains(enriched);
    }

    @Test
    void findReturnsEmptyWhenNothingAttached() {
        UpdateContext ctx = new UpdateContext(1, 100L, -200L, "/echo");

        assertThat(ctx.has(String.class)).isFalse();
        assertThat(ctx.find(String.class)).isEmpty();
    }

    @Test
    void attachIgnoresNull() {
        UpdateContext ctx = new UpdateContext(1, 100L, -200L, "/echo");

        ctx.attach(null);

        assertThat(ctx.has(String.class)).isFalse();
    }
}

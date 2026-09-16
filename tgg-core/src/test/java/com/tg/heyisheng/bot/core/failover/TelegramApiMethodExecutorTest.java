package com.tg.heyisheng.bot.core.failover;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回复执行器测试。
 *
 * <p>本类存在的理由：库的 {@code LongPollingUpdateConsumer.consume} 返回 {@code void}，
 * 不会执行 handler 产出的 {@code BotApiMethod}——webhook 模式下那是靠
 * 库的 {@code @RestController} 把它作为 HTTP 响应体交回 Telegram 完成的。
 * 因此长轮询模式必须由本项目自己把回复发出去。
 */
class TelegramApiMethodExecutorTest {

    private final TelegramApiMethodExecutor executor =
            new TelegramApiMethodExecutor(new OkHttpClient(), new ObjectMapper(), "123456:ABC-DEF");

    @Test
    void buildsTelegramApiUrlWithTokenAndMethod() {
        assertThat(executor.buildUrl("sendMessage").toString())
                .isEqualTo("https://api.telegram.org/bot123456:ABC-DEF/sendMessage");
    }

    @Test
    void urlEncodesMethodPathAsSegment() {
        assertThat(executor.buildUrl("sendMessage").encodedPath())
                .isEqualTo("/bot123456:ABC-DEF/sendMessage");
    }
}

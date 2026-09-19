package com.tg.heyisheng.bot.core.failover;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code getWebhookInfo} 响应 → 三态判定 的分类测试（不依赖网络）。
 *
 * <p>核心：只有「Telegram 明确答复 webhook 不健康」才归 {@link WebhookHealth#UNHEALTHY}；
 * 非 2xx / 响应非法 / {@code ok:false} 一律归 {@link WebhookHealth#UNKNOWN}
 * （探测通道问题，不加诸 webhook）。二者混同会让一次通信故障触发不可逆降级。
 */
class TelegramApiWebhookHealthProbeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(300);

    private static WebhookHealth classify(int statusCode, String body) {
        JsonNode root;
        try {
            root = body == null ? null : MAPPER.readTree(body);
        } catch (Exception ex) {
            root = null;
        }
        return TelegramApiWebhookHealthProbe.classify(statusCode, root, NOW, WINDOW);
    }

    @Test
    void healthyWhenUrlPresentAndNoRecentError() {
        assertThat(classify(200, "{\"ok\":true,\"result\":{\"url\":\"https://x/webhook\"}}"))
                .isEqualTo(WebhookHealth.HEALTHY);
    }

    /** 「没有错误」≠「健康」：url 为空说明 webhook 未配置/已清除 → 群收不到推送，应判不健康。 */
    @Test
    void unhealthyWhenUrlBlank() {
        assertThat(classify(200, "{\"ok\":true,\"result\":{\"url\":\"\"}}"))
                .isEqualTo(WebhookHealth.UNHEALTHY);
        assertThat(classify(200, "{\"ok\":true,\"result\":{}}"))
                .isEqualTo(WebhookHealth.UNHEALTHY);
    }

    @Test
    void unhealthyWhenRecentDeliveryError() {
        long recent = NOW.minusSeconds(10).getEpochSecond();
        assertThat(classify(200,
                "{\"ok\":true,\"result\":{\"url\":\"https://x/webhook\",\"last_error_date\":" + recent + "}}"))
                .isEqualTo(WebhookHealth.UNHEALTHY);
    }

    @Test
    void healthyWhenErrorIsOutsideWindow() {
        long old = NOW.minusSeconds(1000).getEpochSecond();
        assertThat(classify(200,
                "{\"ok\":true,\"result\":{\"url\":\"https://x/webhook\",\"last_error_date\":" + old + "}}"))
                .as("历史错误不应导致永久误判")
                .isEqualTo(WebhookHealth.HEALTHY);
    }

    @Test
    void non2xxIsUnknownNotUnhealthy() {
        assertThat(classify(500, "{\"ok\":true,\"result\":{}}"))
                .as("上游 5xx 是探测通道问题，不能当作 webhook 失效")
                .isEqualTo(WebhookHealth.UNKNOWN);
        assertThat(classify(401, "{\"description\":\"Unauthorized\"}"))
                .as("鉴权失败（token 无效）是配置问题，降级到长轮询同样走不通")
                .isEqualTo(WebhookHealth.UNKNOWN);
        assertThat(classify(429, "{\"ok\":false}")).isEqualTo(WebhookHealth.UNKNOWN);
    }

    @Test
    void okFalseIsUnknown() {
        assertThat(classify(200, "{\"ok\":false,\"description\":\"Too Many Requests\"}"))
                .isEqualTo(WebhookHealth.UNKNOWN);
    }

    @Test
    void malformedBodyIsUnknown() {
        assertThat(classify(200, null)).as("空响应体").isEqualTo(WebhookHealth.UNKNOWN);
        assertThat(classify(200, "not json")).as("非法 JSON").isEqualTo(WebhookHealth.UNKNOWN);
    }
}

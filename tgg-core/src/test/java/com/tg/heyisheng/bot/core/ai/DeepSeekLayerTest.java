package com.tg.heyisheng.bot.core.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L3 DeepSeek 层测试（用 HTTP 替身，不发真实请求）。
 *
 * <p>最要紧的一条是**隐私不变量**：发出去的请求体里只有正文，不得含身份信息。
 * 这条不靠"看代码"保证——用替身捕获请求体逐字断言。
 */
class DeepSeekLayerTest {

    private static final String KEY = "test-key";
    private static final String BASE = "https://api.example.com";

    /** 捕获请求体的替身。 */
    private static final class CapturingClient implements JsonHttpClient {
        private String lastUrl;
        private String lastToken;
        private String lastBody;
        private String response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"risk\\\":\\\"NONE\\\"}\"}}]}";
        private Exception failure;

        @Override
        public String postJson(String url, String bearerToken, String jsonBody) throws Exception {
            this.lastUrl = url;
            this.lastToken = bearerToken;
            this.lastBody = jsonBody;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }

    private static DeepSeekLayer layer(JsonHttpClient client) {
        return new DeepSeekLayer(client, new ObjectMapper(), KEY, BASE, "deepseek-chat");
    }

    @Test
    void parsesRiskLevelFromModelResponse() {
        CapturingClient client = new CapturingClient();
        client.response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"risk\\\":\\\"HIGH\\\",\\\"hardLine\\\":true}\"}}]}";

        var hit = layer(client).inspect("快来投资");

        assertThat(hit).isPresent();
        assertThat(hit.get().riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(hit.get().hardLine()).isTrue();
    }

    @Test
    void noneMeansNoHit() {
        assertThat(layer(new CapturingClient()).inspect("今天天气不错")).isEmpty();
    }

    /** 隐私不变量：出站请求只含正文，不含任何身份标识。 */
    @Test
    void outboundRequestCarriesOnlyTextAndNoIdentity() {
        CapturingClient client = new CapturingClient();

        layer(client).inspect("测试正文内容");

        assertThat(client.lastBody).as("请求体应含正文").contains("测试正文内容");
        assertThat(client.lastBody)
                .as("不得夹带身份信息（userId/chatId/用户名）")
                .doesNotContain("userId", "user_id", "chatId", "chat_id", "from", "username");
        assertThat(client.lastToken).as("鉴权走 Bearer").isEqualTo(KEY);
        assertThat(client.lastUrl).as("端点应为 baseUrl + /chat/completions").endsWith("/chat/completions");
    }

    @Test
    void toleratesCodeFenceWrappedJson() {
        CapturingClient client = new CapturingClient();
        client.response = "{\"choices\":[{\"message\":{\"content\":\"```json\\n{\\\"risk\\\":\\\"LOW\\\"}\\n```\"}}]}";

        assertThat(layer(client).inspect("内容")).isPresent()
                .get().extracting(h -> h.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    /** fail-open：网络失败不抛异常、不误判为违规。 */
    @Test
    void failsOpenOnNetworkError() {
        CapturingClient client = new CapturingClient();
        client.failure = new java.io.IOException("连接超时");

        assertThat(layer(client).inspect("内容")).as("失败应放行而非抛出").isEmpty();
    }

    /** fail-open：非 JSON 响应不得误判为违规（误判会误删正常消息）。 */
    @Test
    void failsOpenOnMalformedResponse() {
        CapturingClient client = new CapturingClient();
        client.response = "这不是 JSON";

        assertThat(layer(client).inspect("内容")).isEmpty();
    }

    /** 缺 key 时该层不工作（也不发请求）——避免带着空鉴权去打接口。 */
    @Test
    void doesNothingWithoutApiKey() {
        CapturingClient client = new CapturingClient();
        DeepSeekLayer noKey = new DeepSeekLayer(client, new ObjectMapper(), "", BASE, "deepseek-chat");

        assertThat(noKey.inspect("内容")).isEmpty();
        assertThat(client.lastBody).as("缺 key 不应发出请求").isNull();
    }

    @Test
    void ignoresBlankText() {
        CapturingClient client = new CapturingClient();

        assertThat(layer(client).inspect("   ")).isEmpty();
        assertThat(client.lastBody).isNull();
    }
}

package com.tg.heyisheng.bot.core.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.core.config.AiConfiguration;
import com.tg.heyisheng.bot.core.moderation.ModerationLayer;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2/L4 本地层与装配开关测试。
 *
 * <p>关键契约：**默认不装配**——本地模型是可选增强，不该在没人开启时凭空出现在流水线里；
 * 也不该在未配端点时白白发请求。
 */
class LocalModelLayerTest {

    private static final String ENDPOINT = "http://localhost:9000/classify";

    /** 捕获请求的替身。 */
    private static final class CapturingClient implements JsonHttpClient {
        private String lastUrl;
        private String lastBody;
        private String response = "{\"risk\":\"NONE\"}";
        private Exception failure;

        @Override
        public String postJson(String url, String bearerToken, String jsonBody) throws Exception {
            this.lastUrl = url;
            this.lastBody = jsonBody;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }

    private static LocalModelLayer layer(JsonHttpClient client, String endpoint) {
        return new LocalModelLayer(client, new ObjectMapper(), "L2-local-ml", endpoint, "L2_LOCAL_ML");
    }

    @Test
    void parsesLocalVerdict() {
        CapturingClient client = new CapturingClient();
        client.response = "{\"risk\":\"MEDIUM\",\"hardLine\":false}";

        assertThat(layer(client, ENDPOINT).inspect("内容")).isPresent()
                .get().extracting(ModerationLayer.LayerHit::riskLevel).isEqualTo(RiskLevel.MEDIUM);
        assertThat(client.lastUrl).isEqualTo(ENDPOINT);
        assertThat(client.lastBody).as("只发正文，不含身份").contains("内容").doesNotContain("userId", "chatId");
    }

    /** 端点未配置时不得发请求（避免打到空地址）。 */
    @Test
    void doesNotCallWhenEndpointMissing() {
        CapturingClient client = new CapturingClient();

        assertThat(layer(client, null).inspect("内容")).isEmpty();
        assertThat(client.lastBody).isNull();
    }

    @Test
    void failsOpenOnLocalServiceError() {
        CapturingClient client = new CapturingClient();
        client.failure = new java.io.IOException("本地服务未启动");

        assertThat(layer(client, ENDPOINT).inspect("内容")).as("本地服务不可用应放行而非抛出").isEmpty();
    }

    @Test
    void noneMeansNoHit() {
        assertThat(layer(new CapturingClient(), ENDPOINT).inspect("正常内容")).isEmpty();
    }

    // ---- 装配开关 ----

    @Configuration
    static class TestConfig {
        @Bean
        JsonHttpClient testClient() {
            return new CapturingClient();
        }

        /** ApplicationContextRunner 不做自动配置，ObjectMapper 需手工提供（AI 层的构造函数依赖它）。 */
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class, AiConfiguration.class)
                .withBean(com.tg.heyisheng.bot.core.moderation.ModerationLayer.class,
                        () -> new com.tg.heyisheng.bot.core.moderation.RegexLayer(List.of()));
    }

    /** 默认（不设任何开关）：只有 L1，L2/L3/L4 都不装配。 */
    @Test
    void noAiLayersByDefault() {
        runner().run(context -> {
            assertThat(context).doesNotHaveBean(DeepSeekLayer.class);
            assertThat(context).doesNotHaveBean(LocalModelLayer.class);
            assertThat(context.getBean(com.tg.heyisheng.bot.core.moderation.ModerationPipeline.class).layerNames())
                    .as("默认只应有 L1")
                    .containsExactly("L1-regex");
        });
    }

    /** 开了 L2 但不配端点：层存在但不会发请求（不因误配而乱打地址）。 */
    @Test
    void l2EnabledWithoutEndpointIsHarmless() {
        runner().withPropertyValues("tgg.ai.local.l2-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(LocalModelLayer.class);
            assertThat(context.getBean(com.tg.heyisheng.bot.core.moderation.ModerationPipeline.class).layerNames())
                    .containsExactly("L1-regex", "L2-local-ml");
        });
    }

    /** 启用 L3 但缺 key：**启动即失败**（fail-fast），不带着空鉴权去打接口。 */
    @Test
    void enablingDeepSeekWithoutKeyFailsFast() {
        runner().withPropertyValues("tgg.ai.deepseek.enabled=true").run(context ->
                assertThat(context.getStartupFailure())
                        .as("缺 key 应启动失败")
                        .isNotNull());
    }
}

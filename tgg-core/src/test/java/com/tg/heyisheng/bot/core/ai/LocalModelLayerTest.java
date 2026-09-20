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

    /**
     * 启用 L3 **且配了 key**：DeepSeekLayer 真的装配进流水线——这是上面那条 fail-fast 断言的
     * <b>成功方向</b>。此前只有「缺 key → 起不来」的反向断言，于是「配齐后能否装配」这条路径
     * 从没被跑过（本项目反复吃亏的「开关默认关闭 → 该装配路径长期假绿」形态）。
     */
    @Test
    void deepseekEnabledWithKeyWiresL3() {
        runner().withPropertyValues(
                        "tgg.ai.deepseek.enabled=true",
                        "tgg.ai.deepseek.api-key=test-key-not-a-real-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DeepSeekLayer.class);
                    assertThat(context.getBean(com.tg.heyisheng.bot.core.moderation.ModerationPipeline.class)
                            .layerNames())
                            .as("L3 应排在 L1 之后（越便宜越靠前，L3 是唯一出站的层）")
                            .containsExactly("L1-regex", "L3-deepseek");
                });
    }

    /**
     * 启用 L4 零样本：装配出第四层并排在流水线**最后**。
     *
     * <p>L4 的开关此前完全没有「打开」方向的测试——它与 L2 同为 {@code LocalModelLayer} 类型，
     * 只有各自开关打开时才出现，故两个方向都要各测一条，否则某天把 L4 的 {@code @ConditionalOnProperty}
     * 写错（如误挂成 L2 的键）也不会有测试变红。
     */
    @Test
    void l4EnabledWiresFourthLayer() {
        runner().withPropertyValues("tgg.ai.local.l4-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(LocalModelLayer.class);
            assertThat(context.getBean(com.tg.heyisheng.bot.core.moderation.ModerationPipeline.class)
                    .layerNames())
                    .containsExactly("L1-regex", "L4-local-zeroshot");
        });
    }
}

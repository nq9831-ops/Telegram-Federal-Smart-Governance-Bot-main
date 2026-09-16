package com.tg.heyisheng.bot.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.common.exception.TggConfigException;
import com.tg.heyisheng.bot.core.ai.DeepSeekLayer;
import com.tg.heyisheng.bot.core.ai.JsonHttpClient;
import com.tg.heyisheng.bot.core.ai.LocalModelLayer;
import com.tg.heyisheng.bot.core.ai.OkHttpJsonHttpClient;
import com.tg.heyisheng.bot.core.ai.TggAiProperties;
import com.tg.heyisheng.bot.core.moderation.ModerationLayer;
import com.tg.heyisheng.bot.core.moderation.ModerationPipeline;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * 模块九 · AI 审核层（L2/L3/L4）装配。
 *
 * <p><b>全部默认关闭</b>：L3 会把消息正文送往第三方，L2/L4 依赖部署方自备的本地模型服务。
 * 默认开启等于默认把用户消息发出去——因此必须显式启用。
 *
 * <p>⚠️ **不启用时本类不产生任何层**，审核退回"仅 L1 正则"，且**不会报错**——
 * 部署时必须显式设置开关（见部署清单）。
 */
@Configuration
@EnableConfigurationProperties(TggAiProperties.class)
public class AiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiConfiguration.class);

    private final TggAiProperties properties;

    public AiConfiguration(TggAiProperties properties) {
        this.properties = properties;
    }

    /**
     * 启用 L3 即必须配置 API key——否则该层会带着空鉴权去打接口（必然 401）并每次都记 WARN。
     * 与其运行期刷错误日志，不如启动期直接失败（与 failover / admission 同款 fail-fast）。
     */
    @PostConstruct
    void requireApiKeyWhenEnabled() {
        if (properties.getDeepseek().isEnabled()) {
            String key = properties.getDeepseek().getApiKey();
            if (key == null || key.isBlank()) {
                throw new TggConfigException(
                        "启用 tgg.ai.deepseek 时必须配置 TGG_DEEPSEEK_API_KEY（否则该层恒失败并刷日志）");
            }
            log.warn("L3 DeepSeek 审核已启用：消息正文将发送至 {}（仅正文，不含身份信息）。"
                            + "请确认已就该第三方处理行为作出声明（见 PRIVACY.md 与部署清单）。",
                    properties.getDeepseek().getBaseUrl());
        }
    }

    @Bean
    public JsonHttpClient aiJsonHttpClient() {
        return new OkHttpJsonHttpClient(Duration.ofMillis(properties.getDeepseek().getTimeoutMs()));
    }

    /**
     * L3 层：仅在 {@code tgg.ai.deepseek.enabled=true} 时创建。
     *
     * <p>未启用时该 bean 不存在，{@link #moderationPipeline} 收集到的层里自然没有它——
     * 流水线对"缺席的层"无需额外分支。
     */
    @Bean
    @ConditionalOnProperty(prefix = "tgg.ai.deepseek", name = "enabled", havingValue = "true")
    public DeepSeekLayer deepSeekLayer(JsonHttpClient aiJsonHttpClient, ObjectMapper objectMapper) {
        return new DeepSeekLayer(aiJsonHttpClient, objectMapper,
                properties.getDeepseek().getApiKey(),
                properties.getDeepseek().getBaseUrl(),
                properties.getDeepseek().getModel());
    }

    /**
     * L2 · 本地 ML 分类器：仅在 {@code tgg.ai.local.l2-enabled=true} 时创建。
     *
     * <p>层名 {@code L2-local-ml} 决定它在流水线中的位置（按层名排序）——排在 L3 之前，
     * 因此本地命中时**根本不会调用 DeepSeek**（省费用，也少把内容送出去一次）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "tgg.ai.local", name = "l2-enabled", havingValue = "true")
    public LocalModelLayer l2LocalModelLayer(JsonHttpClient aiJsonHttpClient, ObjectMapper objectMapper) {
        return new LocalModelLayer(aiJsonHttpClient, objectMapper, "L2-local-ml",
                properties.getLocal().getL2Endpoint(), "L2_LOCAL_ML");
    }

    /** L4 · 本地零样本：仅在 {@code tgg.ai.local.l4-enabled=true} 时创建。排在最后。 */
    @Bean
    @ConditionalOnProperty(prefix = "tgg.ai.local", name = "l4-enabled", havingValue = "true")
    public LocalModelLayer l4LocalModelLayer(JsonHttpClient aiJsonHttpClient, ObjectMapper objectMapper) {
        return new LocalModelLayer(aiJsonHttpClient, objectMapper, "L4-local-zeroshot",
                properties.getLocal().getL4Endpoint(), "L4_LOCAL_ZEROSHOT");
    }

    /**
     * 四层流水线：把容器里**所有** {@link ModerationLayer} bean（L1 必有，L2/L3/L4 按各自开关）
     * 按层名排序后组成流水线——{@code L1-regex} < {@code L2-*} < {@code L3-deepseek} < {@code L4-*}，
     * 即**最便宜、最可能命中的层排最前**，命中即短路，省掉后续（尤其外部）调用。
     *
     * <p>层名排序而非手工列举：加层时只需新增 bean，不必改这里（也就不会漏配）。
     */
    @Bean
    public ModerationPipeline moderationPipeline(List<ModerationLayer> layers) {
        List<ModerationLayer> ordered = layers.stream()
                .sorted(Comparator.comparing(ModerationLayer::name))
                .toList();
        log.info("审核流水线已装配层（按询问顺序）：{}", ordered.stream().map(ModerationLayer::name).toList());
        return new ModerationPipeline(ordered);
    }
}

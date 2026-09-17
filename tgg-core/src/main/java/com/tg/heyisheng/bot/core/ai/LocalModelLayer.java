package com.tg.heyisheng.bot.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.core.moderation.ModerationLayer;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * L2 / L4 · 本地模型层（接入位）。
 *
 * <p><b>为什么只做"接入位"而不内置具体模型</b>：本地推理（ML 分类器 / 零样本）需要部署方自备模型服务
 * （GPU 或独立进程）。本项目开发机没有这类服务，内置任何具体实现都**无法验证**——
 * 而"写进仓库却验不了的代码"正是本项目反复警惕的东西。
 * 因此这里定义契约与解析逻辑，实际推理端点由配置提供。
 *
 * <p><b>默认不装配</b>：见 {@code AiConfiguration}——{@code tgg.ai.local.l2-enabled} /
 * {@code l4-enabled} 均为 false 时不产生 bean，流水线里自然没有它。
 *
 * <p><b>隐私</b>：与 L3 同一口径——只发正文，不含身份信息（入参本就只有文本）。
 * 本地推理不构成第三方传输，这是设计文档 §12 决定 1 选择"本地安全模型"的收益所在。
 */
public class LocalModelLayer implements ModerationLayer {

    private static final Logger log = LoggerFactory.getLogger(LocalModelLayer.class);

    private final JsonHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String layerName;
    private final String endpoint;
    private final String ruleId;

    public LocalModelLayer(JsonHttpClient httpClient, ObjectMapper objectMapper,
                           String layerName, String endpoint, String ruleId) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.layerName = layerName;
        this.endpoint = endpoint;
        this.ruleId = ruleId;
    }

    @Override
    public Optional<LayerHit> inspect(String text) {
        if (text == null || text.isBlank() || endpoint == null || endpoint.isBlank()) {
            return Optional.empty();
        }
        try {
            // 约定：本地端点接收 {"text": "..."}，返回 {"risk":"NONE|LOW|MEDIUM|HIGH","hardLine":bool}
            String body = objectMapper.writeValueAsString(
                    objectMapper.createObjectNode().put("text", text));
            String response = httpClient.postJson(endpoint, "", body);
            return parseVerdict(response);
        } catch (Exception ex) {
            // 与 L3 同口径 fail-open：本地服务不可用不该阻断消息处理（L1 仍在防线内）
            log.warn("{} 调用失败，本层放行（注意：此期间该层等于未审核）", layerName, ex);
            return Optional.empty();
        }
    }

    @Override
    public String name() {
        return layerName;
    }

    Optional<LayerHit> parseVerdict(String response) {
        try {
            JsonNode verdict = objectMapper.readTree(response);
            RiskLevel level = RiskLevel.valueOf(verdict.path("risk").asText("NONE"));
            if (level == RiskLevel.NONE) {
                return Optional.empty();
            }
            return Optional.of(new LayerHit(ruleId, level, verdict.path("hardLine").asBoolean(false)));
        } catch (Exception ex) {
            log.warn("{} 响应解析失败，按未命中处理", layerName, ex);
            return Optional.empty();
        }
    }
}

package com.tg.heyisheng.bot.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tg.heyisheng.bot.core.moderation.ModerationLayer;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * L3 · DeepSeek 云端审核层。
 *
 * <p><b>隐私边界（本类最重要的不变量）</b>：{@link #inspect(String)} 的入参只有**正文文本**——
 * 不含 userId / chatId / 群名 / 用户名。这不是巧合，而是 {@link ModerationLayer} 契约的天然边界：
 * 审核只需内容即可判定，身份对判定无用，送出去纯增风险。
 * <b>不要给本层加身份参数</b>——那会把个人标识交给第三方。
 *
 * <p><b>fail-open</b>：网络/超时/解析失败一律**放行**（返回空）并记 WARN。
 * 理由：审核是**增强**（L1 仍在防线内），不该因为第三方抖动而阻断消息处理。
 * 但这等于"该层故障期间等于没审"——日志与部署文档必须写明，别被误当作已覆盖。
 *
 * <p><b>响应必须是严格 JSON</b>：提示词要求模型只回一个 JSON（探针已验证模型会照做）。
 * 解析不出就当作未命中——宁可漏判，不可误判（把正常消息判成违规会误删）。
 */
public class DeepSeekLayer implements ModerationLayer {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekLayer.class);

    static final String LAYER_NAME = "L3-deepseek";
    private static final String RULE_ID = "L3_DEEPSEEK";

    private static final String PROMPT_TEMPLATE = """
            你是社群内容审核器。判断下面这段用户消息是否违规（广告拉人、诈骗、色情、违法）。
            只输出一个 JSON，不要任何解释或 markdown：
            {"risk":"NONE|LOW|MEDIUM|HIGH","hardLine":false}
            其中 hardLine 仅在涉及诈骗/儿童色情等必须立即处置的红线时为 true。

            消息内容：
            """;

    private final JsonHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String endpoint;
    private final String model;

    public DeepSeekLayer(JsonHttpClient httpClient, ObjectMapper objectMapper,
                         String apiKey, String baseUrl, String model) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.endpoint = baseUrl + "/chat/completions";
        this.model = model;
    }

    @Override
    public Optional<LayerHit> inspect(String text) {
        if (text == null || text.isBlank() || apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        try {
            String response = httpClient.postJson(endpoint, apiKey, buildRequestBody(text));
            return parseVerdict(response);
        } catch (Exception ex) {
            // fail-open：第三方不可用不应阻断消息处理（L1 仍在防线内）。
            // 但这意味着"该层故障期间等于没审"——必须留痕。
            log.warn("L3 DeepSeek 调用失败，本层放行（注意：此期间该层等于未审核）", ex);
            return Optional.empty();
        }
    }

    @Override
    public String name() {
        return LAYER_NAME;
    }

    /** 组装请求体：**只含正文**，不含任何身份信息。 */
    String buildRequestBody(String text) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0);
        root.put("max_tokens", 64);

        ObjectNode userMessage = root.putArray("messages").addObject();
        userMessage.put("role", "user");
        userMessage.put("content", PROMPT_TEMPLATE + text);

        return objectMapper.writeValueAsString(root);
    }

    /** 解析模型返回；非 JSON / 字段缺失 / 等级 NONE 都视为未命中。 */
    Optional<LayerHit> parseVerdict(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                log.warn("L3 响应缺少 choices[0].message.content，按未命中处理");
                return Optional.empty();
            }
            String json = stripCodeFence(content.asText());
            JsonNode verdict = objectMapper.readTree(json);
            RiskLevel level = RiskLevel.valueOf(verdict.path("risk").asText("NONE"));
            if (level == RiskLevel.NONE) {
                return Optional.empty();
            }
            return Optional.of(new LayerHit(RULE_ID, level, verdict.path("hardLine").asBoolean(false)));
        } catch (Exception ex) {
            // 解析失败按未命中——宁可漏判，不可把正常消息误判为违规（那会误删）
            log.warn("L3 响应解析失败，按未命中处理", ex);
            return Optional.empty();
        }
    }

    /** 模型偶尔会用 ```json 包裹，剥掉围栏再解析。 */
    private static String stripCodeFence(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, lastFence).trim();
    }
}

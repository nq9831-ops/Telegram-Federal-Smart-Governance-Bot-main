package com.tg.heyisheng.bot.core.failover;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 通过 Telegram 的 {@code getWebhookInfo} 判断 webhook 是否健康。
 *
 * <p><b>判定必须区分「探测失败」与「webhook 失效」</b>——返回 {@link WebhookHealth} 三态：
 *
 * <table border="1">
 *   <caption>响应 → 判定</caption>
 *   <tr><th>情形</th><th>判定</th><th>理由</th></tr>
 *   <tr><td>2xx 且 {@code ok:true}，{@code url} 非空，无窗口内 {@code last_error_date}</td>
 *       <td>{@link WebhookHealth#HEALTHY}</td><td>Telegram 确认 webhook 正常</td></tr>
 *   <tr><td>2xx 且 {@code ok:true}，{@code url} 为空</td>
 *       <td>{@link WebhookHealth#UNHEALTHY}</td><td>webhook 未配置/已清除 → 群收不到推送，应降级</td></tr>
 *   <tr><td>2xx 且 {@code ok:true}，{@code last_error_date} 落在 {@code errorWindow} 内</td>
 *       <td>{@link WebhookHealth#UNHEALTHY}</td><td>最近有投递错误</td></tr>
 *   <tr><td>非 2xx / 响应体非法 / {@code ok:false} / 网络异常</td>
 *       <td>{@link WebhookHealth#UNKNOWN}</td><td>没拿到可信结论——绝不当成 webhook 失效</td></tr>
 * </table>
 *
 * <p>用时间窗而非"是否曾经出错"，避免历史错误导致永久误判。
 *
 * <p><b>未验证声明</b>：本类依赖真实 Telegram API 与有效 bot token，
 * 本机无法验证；须在公网部署后确认。
 */
public class TelegramApiWebhookHealthProbe implements WebhookHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(TelegramApiWebhookHealthProbe.class);
    private static final String API_BASE = "https://api.telegram.org";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String botToken;
    private final Duration errorWindow;

    public TelegramApiWebhookHealthProbe(OkHttpClient httpClient,
                                         ObjectMapper objectMapper,
                                         String botToken,
                                         Duration errorWindow) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.botToken = botToken;
        this.errorWindow = errorWindow;
    }

    @Override
    public WebhookHealth probe() {
        try {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(API_BASE))
                    .newBuilder()
                    .addPathSegment("bot" + botToken)
                    .addPathSegment("getWebhookInfo")
                    .build();

            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody body = response.body();
                JsonNode root = null;
                if (body != null) {
                    try {
                        root = objectMapper.readTree(body.string());
                    } catch (Exception parseFailure) {
                        root = null; // 响应体非法 → 无可信结论（classify 会归 UNKNOWN）
                    }
                }
                WebhookHealth health = classify(response.code(), root, Instant.now(), errorWindow);
                if (health != WebhookHealth.HEALTHY) {
                    log.warn("webhook 健康探测（status={}）判定为 {}——仅 UNHEALTHY 计入降级计数",
                            response.code(), health);
                }
                return health;
            }
        } catch (Exception ex) {
            // 探测通道本身出问题（网络不可达 / DNS / 超时）——无可信结论，不计入降级。
            // 若把它当 UNHEALTHY，一次通信故障会触发（不可逆的）降级到长轮询，而长轮询
            // 走的正是同一条不可达的通路，降级既无用又永久。
            log.warn("getWebhookInfo 探测失败（未取得可信结论，不计入降级）：{}", ex.toString());
            return WebhookHealth.UNKNOWN;
        }
    }

    /**
     * 状态码 + 已解析响应体 → 三态判定。
     * 抽成静态方法便于用真实响应体直接验证分类（同 {@code TelegramGroupLinkVerifier.classify}）。
     *
     * @param statusCode  HTTP 状态码
     * @param root        已解析的响应体；{@code null} 表示响应体缺失或非法
     * @param now         当前时刻（注入以便测试）
     * @param errorWindow 错误时间窗
     */
    static WebhookHealth classify(int statusCode, JsonNode root, Instant now, Duration errorWindow) {
        if (statusCode != 200 || root == null) {
            // 非 2xx（5xx / 429 / 鉴权失败）或响应体非法：拿不到可信答复，不加诸 webhook 本身
            return WebhookHealth.UNKNOWN;
        }
        if (!root.path("ok").asBoolean(false)) {
            // Telegram 明确回绝（如 token 无效、限流）：同样无法据以判断 webhook 状态
            return WebhookHealth.UNKNOWN;
        }
        JsonNode result = root.path("result");

        // 「没有错误」≠「健康」：webhook 从未配置成功时（url 为空），last_error_date 也不存在。
        // 若只看后者，会恒判健康 → 连续失败计数永不增长 → 降级永不触发。
        String webhookUrl = result.path("url").asText("");
        if (webhookUrl.isBlank()) {
            // webhook 未配置/已被清除 → 群确实收不到推送，这是可信的「不健康」
            return WebhookHealth.UNHEALTHY;
        }

        long lastErrorDate = result.path("last_error_date").asLong(0L);
        if (lastErrorDate <= 0L) {
            return WebhookHealth.HEALTHY;
        }
        return Instant.ofEpochSecond(lastErrorDate).isBefore(now.minus(errorWindow))
                ? WebhookHealth.HEALTHY
                : WebhookHealth.UNHEALTHY;
    }
}

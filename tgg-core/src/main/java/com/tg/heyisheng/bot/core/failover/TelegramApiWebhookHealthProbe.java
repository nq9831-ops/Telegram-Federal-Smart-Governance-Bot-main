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
 * <p>判定规则：{@code result.last_error_date} 为空 → 健康；
 * 否则若最后一次错误发生在 {@code errorWindow} 之内 → 不健康。
 * 用时间窗而非"是否曾经出错"，避免历史错误导致永久误判。
 *
 * <p><b>任何异常（网络不可达、响应非法）都计为不健康</b>——降级本该在通信出问题时触发。
 *
 * <p><b>未验证声明</b>：本类依赖真实 Telegram API 与有效 bot token，
 * 本机无法验证；须在公网部署后确认（见 docs/DEPLOYMENT-VERIFICATION.md）。
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
    public boolean isHealthy() {
        try {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(API_BASE))
                    .newBuilder()
                    .addPathSegment("bot" + botToken)
                    .addPathSegment("getWebhookInfo")
                    .build();

            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("getWebhookInfo 返回非 2xx：{}", response.code());
                    return false;
                }
                ResponseBody body = response.body();
                if (body == null) {
                    return false;
                }
                JsonNode root = objectMapper.readTree(body.string());
                if (!root.path("ok").asBoolean(false)) {
                    return false;
                }
                JsonNode result = root.path("result");
                long lastErrorDate = result.path("last_error_date").asLong(0L);
                if (lastErrorDate <= 0L) {
                    return true;
                }
                return Instant.ofEpochSecond(lastErrorDate)
                        .isBefore(Instant.now().minus(errorWindow));
            }
        } catch (Exception ex) {
            // 探不通即视为不健康——这正是要降级的场景
            log.warn("getWebhookInfo 探测失败，判定为不健康：{}", ex.toString());
            return false;
        }
    }
}

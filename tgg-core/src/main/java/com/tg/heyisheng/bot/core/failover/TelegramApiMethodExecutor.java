package com.tg.heyisheng.bot.core.failover;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;

import java.util.Objects;

/**
 * 把 handler 产出的 {@link BotApiMethod} 真正发往 Telegram。
 *
 * <p><b>为什么需要它</b>：webhook 模式下，库的 {@code @RestController} 会把
 * handler 的返回值当作 HTTP 响应体交回 Telegram 执行；而长轮询模式下
 * {@code LongPollingUpdateConsumer.consume} 返回 {@code void}，
 * <b>库不做任何执行</b>——不补这一步，降级后会出现"消息收得到、回复发不出"。
 *
 * <p>URL 形式与库自身一致（{@code https://api.telegram.org/bot<token>/<method>}），
 * method 取自 {@code BotApiMethod.getMethod()}。
 *
 * <p><b>未验证声明</b>：真实发送需有效 token 与 Telegram 网络，本机不可验，
 * 须公网部署后确认。
 */
public class TelegramApiMethodExecutor {

    private static final Logger log = LoggerFactory.getLogger(TelegramApiMethodExecutor.class);
    private static final String API_BASE = "https://api.telegram.org";
    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String botToken;

    public TelegramApiMethodExecutor(OkHttpClient httpClient, ObjectMapper objectMapper, String botToken) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.botToken = botToken;
    }

    /**
     * 发送一个 Telegram API 调用。
     *
     * <p>失败只记录、不抛出——单条回复发不出去不应中断整批 update 的处理。
     *
     * @return {@code true} = 2xx 成功；{@code false} = 未发出或失败（含 {@code method} 为 null）。
     *         调用方若要据此做后续决策（如「移出成功才清理登记」），以本返回值为准。
     */
    public boolean execute(BotApiMethod<?> method) {
        if (method == null) {
            return false;
        }
        String methodName = method.getMethod();
        try {
            String payload = objectMapper.writeValueAsString(method);
            Request request = new Request.Builder()
                    .url(buildUrl(methodName))
                    .post(RequestBody.create(payload, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("发送 {} 失败：HTTP {}", methodName, response.code());
                    return false;
                }
                return true;
            }
        } catch (Exception ex) {
            log.warn("发送 {} 时发生异常", methodName, ex);
            return false;
        }
    }

    HttpUrl buildUrl(String methodName) {
        return Objects.requireNonNull(HttpUrl.parse(API_BASE))
                .newBuilder()
                .addPathSegment("bot" + botToken)
                .addPathSegment(methodName)
                .build();
    }
}

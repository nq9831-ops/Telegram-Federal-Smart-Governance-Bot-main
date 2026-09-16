package com.tg.heyisheng.bot.core.ai;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.time.Duration;

/**
 * OkHttp 实现的最小 JSON POST 客户端。
 *
 * <p>与 {@code TelegramApiMethodExecutor} 同款做法（项目已有 OkHttp，不引新依赖）。
 */
public class OkHttpJsonHttpClient implements JsonHttpClient {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient httpClient;

    public OkHttpJsonHttpClient(Duration timeout) {
        this.httpClient = new OkHttpClient.Builder()
                .callTimeout(timeout)
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .build();
    }

    @Override
    public String postJson(String url, String bearerToken, String jsonBody) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + bearerToken)
                .post(RequestBody.create(jsonBody, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("AI 接口返回 HTTP " + response.code());
            }
            return response.body().string();
        }
    }
}

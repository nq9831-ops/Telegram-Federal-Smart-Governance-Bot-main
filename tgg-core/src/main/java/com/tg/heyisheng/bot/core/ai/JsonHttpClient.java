package com.tg.heyisheng.bot.core.ai;

/**
 * 最小 JSON POST 客户端抽象。
 *
 * <p><b>为什么抽出来</b>：L3 会**真的把内容发出去**，因此"发出去的到底是什么"
 * 必须能被测试断言（而不是只看代码）。有了这层替身，测试可捕获请求体、断言其中
 * **不含** userId / chatId 等身份信息。
 *
 * <p>也把 OkHttp 细节挡在审核层之外——审核层只关心"发什么、收到什么"。
 */
@FunctionalInterface
public interface JsonHttpClient {

    /**
     * 发一个 JSON POST。
     *
     * @param url        完整地址
     * @param bearerToken 鉴权 token（L3 用 DeepSeek 的 API key）
     * @param jsonBody   请求体（JSON 字符串）
     * @return 响应体（JSON 字符串）
     * @throws Exception 网络/超时/非 2xx（由调用方决定 fail-open）
     */
    String postJson(String url, String bearerToken, String jsonBody) throws Exception;
}

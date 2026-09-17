package com.tg.heyisheng.bot.listing.verification;

import com.tg.heyisheng.bot.listing.ListingGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 默认探针实现：走 Telegram Bot API 的 {@code getChat} 判断群是否仍可达。
 *
 * <p><b>判定必须保守</b>——「不确定」一律归 {@link VerificationResult#ERROR}（不下架），
 * 只有「Telegram 明确拒绝」才归 {@code FAIL}：
 *
 * <table border="1">
 *   <caption>状态码 → 判定</caption>
 *   <tr><th>响应</th><th>判定</th><th>理由</th></tr>
 *   <tr><td>HTTP 200 且 {@code "ok":true}</td><td>{@code OK}</td><td>群可访问</td></tr>
 *   <tr><td>HTTP 400 / 403</td><td>{@code FAIL}</td><td>Telegram 明确答复：群不存在 / 机器人被移出 / 无权访问</td></tr>
 *   <tr><td>其余（含 200 但响应体非预期、429 限流、5xx、超时、网络异常）</td>
 *       <td>{@code ERROR}</td><td>没有得到可信结论——绝不能当成群失效</td></tr>
 * </table>
 *
 * <p><b>本机不可用是已知事实</b>：没有真实 bot token、没有公网通路时，
 * 本实现必然走 {@code ERROR} 分支（token 为空时直接短路，连请求都不发）。
 * 真实探针的端到端行为<b>只能部署后验证</b>（见 {@code docs/DEPLOYMENT-VERIFICATION.md}）。
 *
 * <p><b>日志不许出现 token</b>：请求 URL 内嵌 bot token，故任何日志都只打印条目 id 与状态码，
 * 绝不打印 URL。
 */
public class TelegramGroupLinkVerifier implements GroupLinkVerifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramGroupLinkVerifier.class);

    /** 默认 API 根地址（可替换：部署方若走代理或自建网关，改这一处即可）。 */
    public static final String DEFAULT_API_BASE = "https://api.telegram.org";

    /** Telegram 成功响应恒为紧凑 JSON：{@code {"ok":true,"result":{...}}}；用正则容忍空格，避免误判。 */
    private static final Pattern OK_TRUE = Pattern.compile("\"ok\"\\s*:\\s*true");

    private final String botToken;
    private final String apiBase;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    /** 生产用构造：默认 API 根地址 + 5 秒连接/请求超时。 */
    public TelegramGroupLinkVerifier(String botToken) {
        this(botToken, DEFAULT_API_BASE,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(5));
    }

    /** 测试/自建网关用构造：HTTP 客户端与超时均可替换。 */
    public TelegramGroupLinkVerifier(String botToken, String apiBase, HttpClient httpClient,
                                     Duration requestTimeout) {
        this.botToken = botToken;
        this.apiBase = apiBase;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public VerificationResult verify(ListingGroup entry) {
        if (botToken == null || botToken.isBlank()) {
            // 没有 token 就不可能有可信结论——短路，连请求都不发（本机默认即此路径）
            log.warn("未配置 bot token（TGG_BOT_TOKEN / tgg.webhook.bot-token）：链接探针不可用，"
                    + "条目 #{} 记为 ERROR（不计入失败次数）。", entry.getId());
            return VerificationResult.ERROR;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(getChatUri(entry))
                    .timeout(requestTimeout)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return classify(response.statusCode(), response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("条目 #{} 探测被中断，记为 ERROR（不计入失败次数）。", entry.getId());
            return VerificationResult.ERROR;
        } catch (Exception ex) {
            // 网络不通 / DNS 失败 / 超时 / URI 非法：探测本身失败 —— 绝不当作群失效
            log.warn("条目 #{} 探测异常（{}），记为 ERROR（不计入失败次数）：{}",
                    entry.getId(), ex.getClass().getSimpleName(), ex.getMessage());
            return VerificationResult.ERROR;
        }
    }

    /** 状态码 + 响应体 → 三态判定。抽成静态方法便于用真实 HTTP 替身直接验证分类。 */
    static VerificationResult classify(int statusCode, String body) {
        String payload = body == null ? "" : body;
        if (statusCode == 200) {
            // 200 但不是 {"ok":true} —— 响应体无法如上预期解释，属「结论不可信」，不是「群失效」
            return OK_TRUE.matcher(payload).find() ? VerificationResult.OK : VerificationResult.ERROR;
        }
        if (statusCode == 400 || statusCode == 403) {
            // Telegram 明确拒绝（chat not found / bot was kicked / not enough rights）
            return VerificationResult.FAIL;
        }
        // 429（限流）、5xx（上游故障）等：探测不可信
        return VerificationResult.ERROR;
    }

    /** 构造 getChat 请求 URI。单独成方法便于以后按需求改为解析邀请链接（当前按 chatId 探测）。 */
    private URI getChatUri(ListingGroup entry) {
        return URI.create("%s/bot%s/getChat?chat_id=%d".formatted(apiBase, botToken, entry.getChatId()));
    }
}

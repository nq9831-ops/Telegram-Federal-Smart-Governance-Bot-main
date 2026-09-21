package com.tg.heyisheng.bot.listing.notify;

import com.tg.heyisheng.bot.listing.ListingGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 真实投递实现：经 Bot API {@code sendMessage} <b>私聊</b>通知提交者「其收录的群已失效下架」。
 *
 * <p><b>为什么需要它</b>：{@link SubmitterNotifier} 此前只有 {@link LoggingSubmitterNotifier}
 * 一种实现——即<b>不论有没有 bot token，提交者都收不到任何告知</b>（「软删」与「申诉入口」都在，
 * 唯独告知缺失）。同一项目里硬红线封禁经 {@code ModerationActionSender} 能真发，而通知永远发不出，
 * 这个不对称就是本类补的洞。装配按 token 是否存在分流（见 {@code ListingConfiguration}）。
 *
 * <p><b>异常契约（接口硬性要求）</b>：<b>本类自行吞掉所有异常</b>，只记日志。
 * 通知发生在「条目已判失效并落库」之后，投递失败不能让下架结果回滚、也不能中断整轮验证任务
 * ——否则一个不可用的通知通道会连锁成「收录库永远清理不掉失效群」。
 *
 * <p><b>日志脱敏（硬约束）</b>：只记<b>条目 id</b>与<b>状态码</b>。
 * 不记 {@code userId}（收件人是个人标识，V5.0 要求哈希化——本类一个都不记，泄漏面为零），
 * 不记请求 URL（里面内嵌 bot token），不记邀请链接（其中的 token 等同入群凭证）。
 *
 * <p><b>状态码语义</b>：Telegram 的失败也是 HTTP 200 + {@code {"ok":false,...}}，
 * 因此只看状态码不够——必须同时确认响应体含 {@code "ok":true}（与
 * {@code TelegramGroupLinkVerifier} 同款判定，避免把「Telegram 明确拒绝」当成投递成功）。
 */
public class TelegramSubmitterNotifier implements SubmitterNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramSubmitterNotifier.class);

    /** 默认 API 根地址（与探针一致，便于部署方同一处替换为代理/自建网关）。 */
    public static final String DEFAULT_API_BASE = "https://api.telegram.org";

    /** Telegram 成功响应恒为紧凑 JSON：{@code {"ok":true,...}}；用正则容忍空格，避免误判。 */
    private static final Pattern OK_TRUE = Pattern.compile("\"ok\"\\s*:\\s*true");

    private final String botToken;
    private final String apiBase;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    /** 生产用构造：默认 API 根地址 + 5 秒超时。 */
    public TelegramSubmitterNotifier(String botToken) {
        this(botToken, DEFAULT_API_BASE,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(5));
    }

    /** 测试/自建网关用构造：HTTP 客户端与超时均可替换。 */
    public TelegramSubmitterNotifier(String botToken, String apiBase, HttpClient httpClient,
                                     Duration requestTimeout) {
        this.botToken = botToken;
        this.apiBase = apiBase;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public void notifyDelisted(ListingGroup entry) {
        Long recipient = entry.getSubmitterUserId();
        if (recipient == null) {
            // 早期数据可能没有提交者；没有收件人就无从投递（不是异常，是数据形态）
            log.warn("收录条目 #{} 无提交者记录，无法通知（提交者本人仍可自行申诉）。", entry.getId());
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(sendMessageUri())
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            formBody(recipient, composeText(entry))))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String payload = response.body() == null ? "" : response.body();
            if (response.statusCode() == 200 && OK_TRUE.matcher(payload).find()) {
                log.info("收录条目 #{} 的下架通知已投递提交者。", entry.getId());
            } else {
                // 含 200 + {"ok":false}（被拉黑 / 用户不存在 / 未 start 过 bot）——如实记状态码，不记 URL
                log.warn("收录条目 #{} 的下架通知投递未成功（HTTP {}），已跳过——"
                        + "通知失败不影响已生效的下架结果。", entry.getId(), response.statusCode());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("收录条目 #{} 的通知投递被中断，已跳过。", entry.getId());
        } catch (Exception ex) {
            // 吞异常契约：网络不通/超时/URI 非法/DNS 失败——只记日志，绝不上抛
            log.warn("收录条目 #{} 的通知投递异常（{}），已跳过：{}",
                    entry.getId(), ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private URI sendMessageUri() {
        return URI.create("%s/bot%s/sendMessage".formatted(apiBase, botToken));
    }

    private static String formBody(long recipient, String text) {
        return "chat_id=" + recipient + "&text="
                + URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    /**
     * 通知正文：告知下架事实 + <b>给出申诉指引</b>（设计文档 §6.4 明确要求）。
     *
     * <p>含条目编号是刻意的：申诉命令需要它（{@code /listing_appeal 编号 理由}），
     * 没有编号的告知等于把用户引到一个用不了的入口。
     */
    static String composeText(ListingGroup entry) {
        return "你提交收录的群（编号 #" + entry.getId() + "）经定期验证已失效，已从收录库下架。\n"
                + "若你认为这是误判（例如群只是改成了邀请制），可在 7 天异议期内发送：\n"
                + "/listing_appeal " + entry.getId() + " 你的申诉理由";
    }
}

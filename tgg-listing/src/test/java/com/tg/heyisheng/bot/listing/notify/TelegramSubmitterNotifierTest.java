package com.tg.heyisheng.bot.listing.notify;

import com.sun.net.httpserver.HttpServer;
import com.tg.heyisheng.bot.listing.ListingGroup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 下架通知「真投递」的行为测试：起一个本机 {@link HttpServer} 当 Telegram Bot API 的替身，
 * 捕获通知方实际发出的请求。
 *
 * <p><b>为什么用真 HTTP 而不是 mock</b>：mock 能证明「调用了某个方法」，证明不了
 * 「请求真的发出去、路径与收件人对、正文带申诉指引」——而这三样正是本类存在的全部意义。
 * 替身只替换<b>对端</b>，请求的构造与发送仍是真实代码路径。
 *
 * <p><b>覆盖的失败面</b>：Telegram 的失败也是 HTTP 200 + {@code {"ok":false}}（如被拉黑），
 * 只看状态码会把它当成功；网络不通则抛在客户端侧。两条都必须被吞掉——接口契约是
 * 「实现自行吞异常」，否则投递失败会连锁回滚已生效的下架结果。
 */
class TelegramSubmitterNotifierTest {

    private static HttpServer server;
    private static final List<String> PATHS = new CopyOnWriteArrayList<>();
    private static final List<String> BODIES = new CopyOnWriteArrayList<>();
    private static volatile int respondStatus = 200;
    private static volatile String respondBody = "{\"ok\":true,\"result\":{}}";

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            PATHS.add(exchange.getRequestURI().getPath());
            BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = respondBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(respondStatus, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        PATHS.clear();
        BODIES.clear();
        respondStatus = 200;
        respondBody = "{\"ok\":true,\"result\":{}}";
    }

    private static TelegramSubmitterNotifier notifier() {
        return new TelegramSubmitterNotifier("test-token",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                HttpClient.newHttpClient(), Duration.ofSeconds(3));
    }

    private static ListingGroup entry(long id, Long submitterUserId) {
        ListingGroup group = new ListingGroup(-100900777L, "https://t.me/+probe", "群",
                submitterUserId, Instant.now());
        try {
            Field field = ListingGroup.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(group, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法为测试实体设置 id", ex);
        }
        return group;
    }

    @Test
    void sendsSendMessageAddressedToSubmitterWithAppealHint() {
        notifier().notifyDelisted(entry(7L, 42L));

        assertThat(PATHS).as("应当恰好发出一次请求").hasSize(1);
        assertThat(PATHS.get(0))
                .as("路径必须带 token 与 sendMessage 方法名")
                .isEqualTo("/bottest-token/sendMessage");

        String body = BODIES.get(0);
        assertThat(body).as("收件人必须是提交者").contains("chat_id=42");
        assertThat(body).as("告知必须带上条目编号").contains(URLEncoder.encode("#7", StandardCharsets.UTF_8));
        assertThat(body).as("告知必须给出申诉指引（否则等于把用户引到用不了的入口）")
                .contains("listing_appeal");
    }

    @Test
    void skipsEntirelyWhenSubmitterUnknown() {
        notifier().notifyDelisted(entry(7L, null));

        assertThat(PATHS).as("没有收件人就不该发请求，也不该抛异常").isEmpty();
    }

    @Test
    void swallowsHttpFailure() {
        respondStatus = 500;
        respondBody = "{\"ok\":false}";

        assertThatCode(() -> notifier().notifyDelisted(entry(7L, 42L)))
                .as("投递失败不得上抛——下架结果不能因通知失败回滚")
                .doesNotThrowAnyException();
    }

    @Test
    void treatsOkFalseOnHttp200AsFailureWithoutThrowing() {
        // Telegram 的典型失败形态：状态码 200、body 里 ok:false（被拉黑 / 用户从未 start 过 bot）
        respondBody = "{\"ok\":false,\"description\":\"bot was blocked by the user\"}";

        assertThatCode(() -> notifier().notifyDelisted(entry(7L, 42L)))
                .doesNotThrowAnyException();
    }

    @Test
    void swallowsNetworkFailure() {
        TelegramSubmitterNotifier unreachable = new TelegramSubmitterNotifier("t",
                "http://127.0.0.1:1", HttpClient.newHttpClient(), Duration.ofMillis(200));

        assertThatCode(() -> unreachable.notifyDelisted(entry(7L, 42L)))
                .as("网络不通/DNS 失败/连接被拒都必须被吞掉")
                .doesNotThrowAnyException();
    }

    @Test
    void composeTextMentionsEntryIdAndAppealCommand() {
        String text = TelegramSubmitterNotifier.composeText(entry(12L, 42L));

        assertThat(text).contains("#12").contains("/listing_appeal 12");
    }
}

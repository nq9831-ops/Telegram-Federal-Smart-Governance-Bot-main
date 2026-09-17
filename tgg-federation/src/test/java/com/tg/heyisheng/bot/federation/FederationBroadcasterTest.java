package com.tg.heyisheng.bot.federation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tg.heyisheng.bot.credit.CreditPenaltyOrder;
import com.tg.heyisheng.bot.credit.PenaltySigner;
import com.tg.heyisheng.bot.credit.PenaltyType;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站广播的单测：向所有节点各发一次；**单点失败不影响其他节点**。
 *
 * <p>用 JDK 内置 {@link HttpServer} 起真实 HTTP 服务做替身（不引 MockWebServer 依赖），
 * 断言的是"对端真的收到了这条 JSON"——而非"方法被调用过"。
 */
class FederationBroadcasterTest {

    private HttpServer serverA;
    private HttpServer serverB;
    private final List<String> receivedByA = new ArrayList<>();
    private final List<String> receivedByB = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void startServers() throws IOException {
        serverA = startServer(receivedByA);
        serverB = startServer(receivedByB);
    }

    @AfterEach
    void stopServers() {
        if (serverA != null) {
            serverA.stop(0);
        }
        if (serverB != null) {
            serverB.stop(0);
        }
    }

    private static HttpServer startServer(List<String> sink) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/federation/penalty", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            sink.add(body);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static FederationNode node(HttpServer server) {
        // 公钥在广播侧不参与（广播只发送），给一个占位公钥即可
        KeyPair kp;
        try {
            kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return new FederationNode(baseUrl(server), kp.getPublic());
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static CreditPenaltyOrder signedOrder() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String priv = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
            return new PenaltySigner(priv).signAndAttach(new CreditPenaltyOrder(
                    "order-1", CreditSubjectType.INDIVIDUAL, 42L,
                    PenaltyType.REPORT_TO_FEDERATION, Instant.ofEpochMilli(1_700_000_000_000L), null));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void broadcastsToEveryConfiguredNode() {
        FederationBroadcaster broadcaster = new FederationBroadcaster(
                List.of(node(serverA), node(serverB)), new OkHttpClient(), objectMapper);

        broadcaster.publish(signedOrder());

        assertThat(receivedByA).as("节点 A 应收到广播").hasSize(1);
        assertThat(receivedByB).as("节点 B 应收到广播").hasSize(1);
        assertThat(receivedByA.get(0)).contains("\"orderId\":\"order-1\"");
    }

    @Test
    void oneNodeFailureDoesNotBlockOthers() {
        // B 指向一个不可达端口 → 广播 B 失败，但 A 仍必须收到（尽力而为、不互相拖累）
        FederationNode unreachable = new FederationNode(
                "http://127.0.0.1:1", node(serverA).publicKey());
        FederationBroadcaster broadcaster = new FederationBroadcaster(
                List.of(unreachable, node(serverA)), new OkHttpClient(), objectMapper);

        broadcaster.publish(signedOrder());

        assertThat(receivedByA).as("一个节点不可达不得阻断其他节点").hasSize(1);
    }
}

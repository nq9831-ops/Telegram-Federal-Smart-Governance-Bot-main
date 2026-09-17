package com.tg.heyisheng.bot.federation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tg.heyisheng.bot.credit.CreditPenaltyOrder;
import com.tg.heyisheng.bot.credit.PenaltyOrderPublisher;
import com.tg.heyisheng.bot.credit.PenaltySigner;
import com.tg.heyisheng.bot.credit.PenaltyType;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块八端到端测试：<b>不做中间层 mock</b>。
 *
 * <ul>
 *   <li><b>出站</b>：本节点产出的处罚令经 {@link FederationBroadcaster} 走**真实 HTTP**
 *       发到本机 {@link HttpServer} 对端，断言对端真的收到了这条 JSON；</li>
 *   <li><b>入站</b>：对端签名的令经 MockMvc 打到 {@code POST /federation/penalty}，
 *       走真实 Controller → Service → 验签 → 落库，断言行被写入。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "tgg.federation.enabled=true",
        "tgg.federation.admins=42"
})
@AutoConfigureMockMvc
class FederationEndToEndIT {

    /** 对端（本机真实 HTTP 服务）：既做"接收我们广播"的替身，也是"给我们发令"的签名方。 */
    private static final KeyPair PEER_KEY = keyPair();
    private static final List<String> PEER_RECEIVED = new CopyOnWriteArrayList<>();
    private static HttpServer peerServer;
    private static int peerPort;

    static {
        try {
            peerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            peerServer.createContext("/federation/penalty", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                PEER_RECEIVED.add(body);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            peerServer.start();
            peerPort = peerServer.getAddress().getPort();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @DynamicPropertySource
    static void federationProps(DynamicPropertyRegistry registry) {
        // 对端清单：真实替换品的地址 + 它的公钥（我们用它验签入站令）
        registry.add("tgg.federation.nodes",
                () -> "http://127.0.0.1:" + peerPort + "|" + b64(PEER_KEY.getPublic().getEncoded()));
        // 本节点签发私钥（模块七签名用）
        registry.add("tgg.credit.enabled", () -> "true");
        registry.add("tgg.credit.private-key", () -> b64(PEER_KEY.getPrivate().getEncoded()));
    }

    /** 硬红线封禁走主动通道——测试里换成 noop，避免真发 HTTP 到 Telegram。 */
    @TestConfiguration
    static class Deps {
        @Bean
        @Primary
        ModerationActionSender noopActionSender() {
            return ModerationActionSender.noop();
        }
    }

    @AfterAll
    static void stopPeer() {
        if (peerServer != null) {
            peerServer.stop(0);
        }
    }

    @Autowired
    private PenaltyOrderPublisher publisher;

    @Autowired
    private FederationPenaltyRepository penaltyRepository;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void clear() {
        PEER_RECEIVED.clear();
        penaltyRepository.deleteAll();
    }

    @Test
    void outboundBroadcastReachesPeerOverRealHttp() {
        // publisher 应为联邦广播器（@Primary 覆盖模块七的 noop）
        assertThat(publisher).isInstanceOf(FederationBroadcaster.class);

        CreditPenaltyOrder order = new PenaltySigner(b64(PEER_KEY.getPrivate().getEncoded()))
                .signAndAttach(new CreditPenaltyOrder("e2e-order-1",
                        CreditSubjectType.INDIVIDUAL, 4242L, PenaltyType.REPORT_TO_FEDERATION,
                        Instant.now(), null));

        publisher.publish(order);

        assertThat(PEER_RECEIVED).as("对端必须通过真实 HTTP 收到广播").hasSize(1);
        assertThat(PEER_RECEIVED.get(0)).contains("\"orderId\":\"e2e-order-1\"");
    }

    @Test
    void inboundPenaltyIsVerifiedAndPersisted() throws Exception {
        CreditPenaltyOrder signed = new PenaltySigner(b64(PEER_KEY.getPrivate().getEncoded()))
                .signAndAttach(new CreditPenaltyOrder("e2e-inbound-1",
                        CreditSubjectType.INDIVIDUAL, 4243L, PenaltyType.REPORT_TO_FEDERATION,
                        Instant.now(), null));

        mockMvc.perform(post("/federation/penalty")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        assertThat(penaltyRepository.findByOrderId("e2e-inbound-1"))
                .as("验签通过的入站令必须落库").isPresent();
    }

    @Test
    void inboundPenaltyWithTamperedSignatureIsRejectedAndNotPersisted() throws Exception {
        CreditPenaltyOrder signed = new PenaltySigner(b64(PEER_KEY.getPrivate().getEncoded()))
                .signAndAttach(new CreditPenaltyOrder("e2e-bad-1",
                        CreditSubjectType.INDIVIDUAL, 4244L, PenaltyType.REPORT_TO_FEDERATION,
                        Instant.now(), null));
        // 篡改主体 id，保留原签名
        CreditPenaltyOrder tampered = new CreditPenaltyOrder(
                signed.orderId(), signed.subjectType(), 9999L,
                signed.penaltyType(), signed.issuedAt(), signed.signature());

        mockMvc.perform(post("/federation/penalty")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tampered)))
                .andExpect(status().isForbidden());

        assertThat(penaltyRepository.findByOrderId("e2e-bad-1"))
                .as("验签失败的令不得落库").isEmpty();
    }
}

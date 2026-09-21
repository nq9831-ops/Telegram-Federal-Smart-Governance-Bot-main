package com.tg.heyisheng.bot.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Telegram 登录端到端（模块十一 · TG 用户登录）。
 *
 * <p>证明：<b>服务端验签通过后签发的会话，真能用于访问后台</b>（不建本地账号）；
 * 且<b>篡改数据、MiniApp 签名的数据一律被拒</b>——后者是本类最重要的反向断言。
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token",
        "tgg.webhook.bot-token=it-tg-bot-token"
})
@AutoConfigureMockMvc
class AdminTelegramAuthApiIT {

    private static final String BOT_TOKEN = "it-tg-bot-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** 用 Login Widget 算法签一条数据。 */
    private static Map<String, String> widgetSigned(long userId) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(userId));
        data.put("first_name", "Tg");
        data.put("auth_date", String.valueOf(Instant.now().getEpochSecond()));
        data.put("hash", hash(sha256(BOT_TOKEN), dataCheckString(data)));
        return data;
    }

    /** 用 MiniApp 算法签一条数据（secret_key 构造与 Widget 相反）。 */
    private static Map<String, String> miniAppSigned(long userId) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(userId));
        data.put("auth_date", String.valueOf(Instant.now().getEpochSecond()));
        byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), BOT_TOKEN);
        data.put("hash", hash(secret, dataCheckString(data)));
        return data;
    }

    private static String dataCheckString(Map<String, String> data) {
        return data.entrySet().stream()
                .filter(e -> !"hash".equals(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private static byte[] sha256(String v) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] hmac(byte[] key, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hash(byte[] key, String msg) {
        return HexFormat.of().formatHex(hmac(key, msg));
    }

    @Test
    void validWidgetLoginIssuesUsableSession() throws Exception {
        Map<String, String> data = widgetSigned(424242L);

        String json = mockMvc.perform(post("/admin/auth/telegram")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(data)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectType").value("TG_USER"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(json).get("token").asText();
        mockMvc.perform(get("/admin/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectType").value("TG_USER"))
                .andExpect(jsonPath("$.subjectId").value(424242));
    }

    @Test
    void tamperedDataIsRejected() throws Exception {
        Map<String, String> data = widgetSigned(424242L);
        data.put("id", "999");   // 篡改 id，hash 不再匹配

        mockMvc.perform(post("/admin/auth/telegram")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(data)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void miniAppSignedDataIsRejected() throws Exception {
        Map<String, String> mini = miniAppSigned(424242L);

        mockMvc.perform(post("/admin/auth/telegram")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mini)))
                .andExpect(status().isUnauthorized());
    }
}

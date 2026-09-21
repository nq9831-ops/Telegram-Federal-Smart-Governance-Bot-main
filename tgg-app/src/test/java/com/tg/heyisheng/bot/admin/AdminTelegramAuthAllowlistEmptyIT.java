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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TG 登录白名单为 <b>空</b> 时的 fail-closed 行为（模块十一 · TG 用户登录授权闸门）。
 *
 * <p>与 {@link AdminTelegramAuthApiIT} 的区别：本类<b>刻意不配</b>
 * {@code tgg.admin.tg-login-allowlist}——证明「没配授权名单」不会退化成「授权所有人」，
 * 而是 <b>无人可登</b>。验签合法（同 {@link AdminTelegramAuthApiIT} 的签名算法）也不例外。
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token",
        "tgg.webhook.bot-token=it-tg-bot-token"
        // 刻意不配 tgg.admin.tg-login-allowlist → 空 → fail-closed
})
@AutoConfigureMockMvc
class AdminTelegramAuthAllowlistEmptyIT {

    private static final String BOT_TOKEN = "it-tg-bot-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static Map<String, String> widgetSigned(long userId) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(userId));
        data.put("first_name", "Tg");
        data.put("auth_date", String.valueOf(Instant.now().getEpochSecond()));
        data.put("hash", hash(sha256(BOT_TOKEN), dataCheckString(data)));
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
    void emptyAllowlistRejectsEveryTelegramLogin() throws Exception {
        Map<String, String> data = widgetSigned(424242L);   // 签名完全合法，但白名单为空

        mockMvc.perform(post("/admin/auth/telegram")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(data)))
                .andExpect(status().isForbidden());
    }
}

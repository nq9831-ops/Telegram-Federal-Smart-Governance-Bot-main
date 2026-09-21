package com.tg.heyisheng.bot.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「我的内容」数据范围端到端（模块十一 · 数据范围）。
 *
 * <p><b>负向测试是重点</b>：以 A 的身份查，必须<b>看不到</b> B 的行——不是「前端不显示」，
 * 而是响应体里就<b>没有</b>。这是「数据范围过滤在查询条件里」的可观察证据。
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token",
        "tgg.webhook.bot-token=it-my-bot-token",
        // TG 登录现需白名单：本 IT 以 USER_A/USER_B 身份登录，两者都须在名单内
        "tgg.admin.tg-login-allowlist=100001,100002"
})
@AutoConfigureMockMvc
class MyContentApiIT {

    private static final String BOT_TOKEN = "it-my-bot-token";
    private static final long USER_A = 100001L;
    private static final long USER_B = 100002L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.tg.heyisheng.bot.admin.identity.AdminAccountRepository adminAccounts;

    @BeforeEach
    void setUp() {
        cleanup();
        insert(-100900001L, "A 的群", USER_A);
        insert(-100900002L, "B 的群", USER_B);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM listing_groups WHERE submitter_user_id IN (?, ?)", USER_A, USER_B);
    }

    private void insert(long chatId, String title, long submitter) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO listing_groups "
                        + "(chat_id, invite_link, title, submitter_user_id, status, fail_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE', 0, ?, ?)",
                chatId, "https://t.me/+demo", title, submitter, now, now);
    }

    /** 用 Login Widget 算法签一条数据并以该 TG 用户登录，返回会话令牌。 */
    private String loginAsTelegramUser(long userId) throws Exception {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(userId));
        data.put("auth_date", String.valueOf(Instant.now().getEpochSecond()));
        data.put("hash", HexFormat.of().formatHex(hmac(sha256(BOT_TOKEN), dataCheckString(data))));

        String json = mockMvc.perform(post("/admin/auth/telegram")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(data)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("token").asText();
    }

    @Test
    void tgUserSeesOnlyOwnRowsAndNeverOthersRow() throws Exception {
        String token = loginAsTelegramUser(USER_A);

        String body = mockMvc.perform(get("/admin/my/listings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("应含自己提交的收录").contains("A 的群");
        assertThat(body).as("绝不包含他人提交的收录（过滤在查询条件里，不是前端不显示）")
                .doesNotContain("B 的群");
    }

    @Test
    void adminAccountHasNoOwnListings() throws Exception {
        // 后台账号没有「自己提交的收录」——空列表，且不会被误当成 TG 用户
        String name = "it-my-super-" + System.nanoTime();
        adminAccounts.save(new com.tg.heyisheng.bot.admin.identity.AdminAccount(name,
                com.tg.heyisheng.bot.admin.identity.PasswordHasher.hash("pw"),
                com.tg.heyisheng.bot.admin.identity.AdminRole.SUPER_ADMIN, Instant.now()));

        String json = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(json).get("token").asText();

        String body = mockMvc.perform(get("/admin/my/listings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isEqualTo("[]");
    }

    @Test
    void missingSessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/my/listings")).andExpect(status().isUnauthorized());
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
}

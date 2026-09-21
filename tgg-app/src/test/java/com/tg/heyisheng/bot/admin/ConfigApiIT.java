package com.tg.heyisheng.bot.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.admin.identity.AdminAccount;
import com.tg.heyisheng.bot.admin.identity.AdminAccountRepository;
import com.tg.heyisheng.bot.admin.identity.AdminRole;
import com.tg.heyisheng.bot.admin.identity.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 配置中心端到端（模块十一 扩展）——真实 Spring 上下文 + 真实 MySQL。
 *
 * <p>鉴权已改为<b>服务端会话</b>（Bearer 会话令牌）。最重的断言仍是
 * 「<b>响应体里不含密钥明文</b>」——密钥泄漏是本特性最严重的失败形态。
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token",
        "tgg.webhook.bot-token=SUPERSECRETBOTTOKEN"
})
@AutoConfigureMockMvc
class ConfigApiIT {

    private static final String USERNAME = "it-config-super";
    private static final String PASSWORD = "it-pass-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminAccountRepository accounts;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        if (accounts.findByUsername(USERNAME).isEmpty()) {
            accounts.save(new AdminAccount(USERNAME, PasswordHasher.hash(PASSWORD),
                    AdminRole.SUPER_ADMIN, Instant.now()));
        }
    }

    private String login() throws Exception {
        String json = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("token").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void missingSessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/config")).andExpect(status().isUnauthorized());
    }

    @Test
    void superAdminCanReadOverview() throws Exception {
        JsonNode array = objectMapper.readTree(read());

        assertThat(array.isArray()).isTrue();
        assertThat(array).as("总览不应为空").isNotEmpty();

        JsonNode hot = findByKey(array, "tgg.admin.overdue-remind-hours");
        assertThat(hot).as("热参数应在总览里").isNotNull();
        assertThat(hot.get("restartRequired").asBoolean()).isFalse();
        assertThat(hot.get("editable").asBoolean()).isTrue();
        assertThat(hot.get("effectiveValue").asText()).isEqualTo("24");
    }

    /** 最重的一条：密钥明文绝不出现在响应体里。 */
    @Test
    void secretValueNeverAppearsInResponse() throws Exception {
        String body = read();

        assertThat(body).as("响应体不得包含任何密钥明文").doesNotContain("SUPERSECRETBOTTOKEN");

        JsonNode token = findByKey(objectMapper.readTree(body), "tgg.webhook.bot-token");
        assertThat(token).isNotNull();
        assertThat(token.get("secret").asBoolean()).isTrue();
        assertThat(token.get("effectiveValue").asText()).isEqualTo("***");
    }

    /** 引导态键（如 webhook secret）标为不可写——即便它是「配置」，也不该有 Web 写入口。 */
    @Test
    void bootstrapKeysAreReadOnly() throws Exception {
        JsonNode webhookSecret = findByKey(objectMapper.readTree(read()), "tgg.webhook.secret");
        assertThat(webhookSecret).isNotNull();
        assertThat(webhookSecret.get("editable").asBoolean()).isFalse();
    }

    private String read() throws Exception {
        return mockMvc.perform(get("/admin/config").header("Authorization", bearer(login())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static JsonNode findByKey(JsonNode array, String key) {
        for (JsonNode node : array) {
            if (key.equals(node.path("key").asText())) {
                return node;
            }
        }
        return null;
    }
}

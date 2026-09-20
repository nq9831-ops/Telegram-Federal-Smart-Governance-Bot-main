package com.tg.heyisheng.bot.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 配置中心只读总览的端到端测试（模块十一 扩展）——真实 Spring 上下文 + 真实 MySQL。
 *
 * <p><b>为什么必须到端到端这一层</b>：门禁在 {@code Filter}、打码在 core 服务、
 * 序列化在 Jackson——三处单测都绿，并不能说明「带上 token 打进来，响应体里不会漏出密钥明文」。
 * 密钥泄漏是本特性最严重的失败形态，故这里直接对**真实 HTTP 响应体**断言「不含密钥明文」。
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token",
        "tgg.moderation.reviewers=777,888",
        // 注入一个「密钥」环境值：用来证明总览**不会**把它回显出来
        "tgg.webhook.bot-token=SUPERSECRETBOTTOKEN"
})
@AutoConfigureMockMvc
class ConfigApiIT {

    private static final String TOKEN = "Bearer test-admin-token";
    private static final long REVIEWER = 777L;
    private static final long OUTSIDER = 999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/config")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonWhitelistedOperatorIsForbidden() throws Exception {
        mockMvc.perform(get("/admin/config")
                        .header("Authorization", TOKEN)
                        .header("X-Operator-Id", String.valueOf(OUTSIDER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void reviewerCanReadOverview() throws Exception {
        String body = read();

        JsonNode array = objectMapper.readTree(body);
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

        assertThat(body)
                .as("响应体不得包含任何密钥明文")
                .doesNotContain("SUPERSECRETBOTTOKEN");

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
        return mockMvc.perform(get("/admin/config")
                        .header("Authorization", TOKEN)
                        .header("X-Operator-Id", String.valueOf(REVIEWER)))
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

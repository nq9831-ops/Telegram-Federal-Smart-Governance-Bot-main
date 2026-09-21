package com.tg.heyisheng.bot.admin;

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

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计视图端到端（模块十一 · 护栏）。
 *
 * <p>守：超管可读；无 {@code AUDIT_READ} 的操作员被拒；无会话 401。
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token"
})
@AutoConfigureMockMvc
class AuditViewApiIT {

    private static final String SUPER = "it-audit-super";
    private static final String OPERATOR = "it-audit-op";
    private static final String PASSWORD = "it-pass-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminAccountRepository accounts;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        ensure(SUPER, AdminRole.SUPER_ADMIN);
        ensure(OPERATOR, AdminRole.OPERATOR);
    }

    private void ensure(String username, AdminRole role) {
        if (accounts.findByUsername(username).isEmpty()) {
            accounts.save(new AdminAccount(username, PasswordHasher.hash(PASSWORD), role, Instant.now()));
        }
    }

    private String login(String username) throws Exception {
        String json = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("token").asText();
    }

    @Test
    void superAdminCanReadAudit() throws Exception {
        mockMvc.perform(get("/admin/audit/recent").header("Authorization", "Bearer " + login(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void operatorWithoutAuditReadIsForbidden() throws Exception {
        mockMvc.perform(get("/admin/audit/recent").header("Authorization", "Bearer " + login(OPERATOR)))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingSessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/audit/recent")).andExpect(status().isUnauthorized());
    }
}

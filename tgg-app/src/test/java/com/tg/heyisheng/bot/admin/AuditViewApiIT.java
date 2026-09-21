package com.tg.heyisheng.bot.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.admin.identity.AdminAccount;
import com.tg.heyisheng.bot.admin.identity.AdminAccountRepository;
import com.tg.heyisheng.bot.admin.identity.AdminRole;
import com.tg.heyisheng.bot.admin.identity.PasswordHasher;
import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.platform.PlatformGrantSource;
import com.tg.heyisheng.bot.core.platform.PlatformPermission;
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
        "tgg.admin.api-token=test-admin-token",
        // 本 IT 的每个用例都要登录；默认 per-IP 20/min 会被「同一上下文内多个 admin IT 的登录」累积打满（429），
        // 与本 IT 要守的「审计读权限」无关，故抬高上限。
        "tgg.admin.login-max-per-minute=1000"
})
@AutoConfigureMockMvc
class AuditViewApiIT {

    private static final String SUPER = "it-audit-super";
    private static final String OPERATOR = "it-audit-op";
    /** 专用于「已授权」用例的账号：与 OPERATOR 分开，避免真写 platform_grants 污染共享前置条件。 */
    private static final String GRANTED = "it-audit-granted";
    private static final String PASSWORD = "it-pass-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminAccountRepository accounts;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformGrantSource grants;

    @BeforeEach
    void setUp() {
        ensure(SUPER, AdminRole.SUPER_ADMIN);
        ensure(OPERATOR, AdminRole.OPERATOR);
        ensure(GRANTED, AdminRole.OPERATOR);
        // 显式保证「未授权操作员」的前置条件：本 IT 真写 platform_grants（共享真库），
        // 不清理会让 operatorWithoutAuditReadIsForbidden 依赖上一次运行的残留。
        grants.revoke(ActorType.ADMIN_ACCOUNT, idOf(OPERATOR), PlatformPermission.AUDIT_READ);
    }

    private long idOf(String username) {
        return accounts.findByUsername(username).orElseThrow().getId();
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

    // ── 主体权限透出：前端据此做显示分区（服务端仍是唯一门禁）──

    @Test
    void loginCarriesAuditReadForGrantedOperator() throws Exception {
        grants.grant(ActorType.ADMIN_ACCOUNT, idOf(GRANTED), PlatformPermission.AUDIT_READ, null);

        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + GRANTED + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("AUDIT_READ")));
    }

    @Test
    void superAdminLoginCarriesAllPermissions() throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + SUPER + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItems(
                        "AUDIT_READ", "CONFIG_WRITE", "REVIEW_DECIDE")));
    }

    @Test
    void meCarriesPermissions() throws Exception {
        mockMvc.perform(get("/admin/auth/me").header("Authorization", "Bearer " + login(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("AUDIT_READ")));
    }

    /**
     * 未授权的操作员：`permissions` 必须是**空数组**，而不是省略字段或回落成「全部」。
     *
     * <p>这条守的是「宁可少分区，不可多分区」——前端据它决定是否显示审计入口。
     */
    @Test
    void ungrantedOperatorLoginCarriesEmptyPermissions() throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + OPERATOR + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").isArray())
                .andExpect(jsonPath("$.permissions").isEmpty());
    }
}

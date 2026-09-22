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
 * 信用账本 / 流水可见面端到端（模块十一 · 只读护栏）。
 *
 * <p>守：超管可读流水与账本；无 {@code CREDIT_READ} 的操作员被拒（403）；无会话 401；
 * 非法 {@code subjectType} 400；持 {@code CREDIT_READ} 的操作员可按主体过滤读取。
 *
 * <p>判据与 {@code AuditViewApiIT} 同源——信用可见面复用后台会话门禁的 fail-closed 口径：
 * 无会话一律 401，有会话但无读权限一律 403。见 {@code CreditQueryController}。
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token",
        // 与本包其余 admin IT 一致：抬高登录限流上限，避免同上下文内多 IT 登录累积触发 429。
        "tgg.admin.login-max-per-minute=1000"
})
@AutoConfigureMockMvc
class CreditQueryApiIT {

    private static final String SUPER = "it-credit-super";
    private static final String OPERATOR = "it-credit-op";
    /** 专用于「已授权」用例的账号：与 OPERATOR 分开，避免真写 platform_grants 污染共享前置条件。 */
    private static final String GRANTED = "it-credit-granted";
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
        // 不清理会让 operatorWithoutCreditReadIsForbidden 依赖上一次运行的残留。
        grants.revoke(ActorType.ADMIN_ACCOUNT, idOf(OPERATOR), PlatformPermission.CREDIT_READ);
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

    // ── 只读可见面：超管天然全权 ──

    @Test
    void superAdminCanReadEvents() throws Exception {
        mockMvc.perform(get("/admin/credit/events").header("Authorization", "Bearer " + login(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").isNumber())
                .andExpect(jsonPath("$.size").isNumber());
    }

    @Test
    void superAdminCanReadScores() throws Exception {
        mockMvc.perform(get("/admin/credit/scores").header("Authorization", "Bearer " + login(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    // ── 无读权限 / 无会话：fail-closed ──

    @Test
    void operatorWithoutCreditReadIsForbidden() throws Exception {
        mockMvc.perform(get("/admin/credit/events").header("Authorization", "Bearer " + login(OPERATOR)))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingSessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/credit/events")).andExpect(status().isUnauthorized());
    }

    @Test
    void missingSessionScoresIsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/credit/scores")).andExpect(status().isUnauthorized());
    }

    // ── 授权操作员：可读，且按主体过滤 ──

    @Test
    void grantedOperatorCanReadEventsWithSubjectFilter() throws Exception {
        grants.grant(ActorType.ADMIN_ACCOUNT, idOf(GRANTED), PlatformPermission.CREDIT_READ, null);

        mockMvc.perform(get("/admin/credit/events")
                        .param("subjectType", "INDIVIDUAL")
                        .param("subjectId", "100001")
                        .header("Authorization", "Bearer " + login(GRANTED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    // ── 入参校验：非法 subjectType 必须 400，而不是 500 / 静默全量 ──

    @Test
    void unknownSubjectTypeIsRejected() throws Exception {
        mockMvc.perform(get("/admin/credit/events")
                        .param("subjectType", "BOGUS")
                        .header("Authorization", "Bearer " + login(SUPER)))
                .andExpect(status().isBadRequest());
    }

    /**
     * 只给 subjectId 不给 subjectType：必须 400。
     *
     * <p>id 空间在三种主体类型间重叠（个人 userId / 群 chatId / 商户 id），单看 id 会串号——
     * 故按 id 过滤强制成对给出类型。
     */
    @Test
    void subjectIdWithoutSubjectTypeIsRejected() throws Exception {
        mockMvc.perform(get("/admin/credit/events")
                        .param("subjectId", "100001")
                        .header("Authorization", "Bearer " + login(SUPER)))
                .andExpect(status().isBadRequest());
    }
}

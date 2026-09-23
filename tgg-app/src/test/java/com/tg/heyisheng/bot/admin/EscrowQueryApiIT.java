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
 * 担保交易订单可见面端到端（模块十二 · 只读护栏，闭合 gap-ESC-05「有写入无查询入口」）。
 *
 * <p><b>为什么这个端点必须有</b>：{@code escrow_orders} 此前只能写（状态机与命令），
 * 运营者与联邦裁决方<b>没有任何界面能看到订单</b>——超时单、争议单会一直躺着没人处理。
 * 这正是本仓既有的 gap-07（处罚历史查询）同型缺口，本次补上。
 *
 * <p>守四态（与 {@code CreditQueryApiIT} / {@code AuditViewApiIT} 同源的 fail-closed 口径）：
 * 无会话 401、有会话但无权限 403、非法入参 400、超管/持权者 200。
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token",
        // 与本包其余 admin IT 一致：抬高登录限流上限，避免同上下文内多 IT 登录累积触发 429。
        "tgg.admin.login-max-per-minute=1000",
        "tgg.escrow.enabled=true"
})
@AutoConfigureMockMvc
class EscrowQueryApiIT {

    private static final String SUPER = "it-escrow-super";
    private static final String OPERATOR = "it-escrow-op";
    private static final String GRANTED = "it-escrow-granted";
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
        // 显式保证「未授权操作员」前置条件：本 IT 真写 platform_grants（共享真库），
        // 不清理会让越权用例依赖上一次运行的残留。
        grants.revoke(ActorType.ADMIN_ACCOUNT, idOf(OPERATOR), PlatformPermission.FEDERATION_ADMIN);
    }

    private void ensure(String username, AdminRole role) {
        if (accounts.findByUsername(username).isEmpty()) {
            accounts.save(new AdminAccount(username, PasswordHasher.hash(PASSWORD), role, Instant.now()));
        }
    }

    private long idOf(String username) {
        return accounts.findByUsername(username).orElseThrow().getId();
    }

    private String login(String username) throws Exception {
        String json = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("token").asText();
    }

    // ── 超管：天然全权 ──

    @Test
    void superAdminCanReadOrders() throws Exception {
        mockMvc.perform(get("/admin/escrow/orders").header("Authorization", "Bearer " + login(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").isNumber())
                .andExpect(jsonPath("$.size").isNumber());
    }

    @Test
    void superAdminCanFilterByState() throws Exception {
        mockMvc.perform(get("/admin/escrow/orders")
                        .param("state", "DISPUTED")
                        .header("Authorization", "Bearer " + login(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    // ── fail-closed：无会话 401、无权限 403 ──

    @Test
    void missingSessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/escrow/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void operatorWithoutEscrowPermissionIsForbidden() throws Exception {
        mockMvc.perform(get("/admin/escrow/orders").header("Authorization", "Bearer " + login(OPERATOR)))
                .andExpect(status().isForbidden());
    }

    /** 持 FEDERATION_ADMIN 的操作员可读——裁决方需要看订单。 */
    @Test
    void federationAdminCanReadOrders() throws Exception {
        grants.grant(ActorType.ADMIN_ACCOUNT, idOf(GRANTED), PlatformPermission.FEDERATION_ADMIN, null);

        mockMvc.perform(get("/admin/escrow/orders").header("Authorization", "Bearer " + login(GRANTED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    // ── 入参校验：非法 state 必须 400，而不是 500 / 静默返回全量 ──

    @Test
    void unknownStateIsRejected() throws Exception {
        mockMvc.perform(get("/admin/escrow/orders")
                        .param("state", "BOGUS")
                        .header("Authorization", "Bearer " + login(SUPER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void orderDetailOfMissingIdIsNotFound() throws Exception {
        mockMvc.perform(get("/admin/escrow/orders/999999999")
                        .header("Authorization", "Bearer " + login(SUPER)))
                .andExpect(status().isNotFound());
    }
}

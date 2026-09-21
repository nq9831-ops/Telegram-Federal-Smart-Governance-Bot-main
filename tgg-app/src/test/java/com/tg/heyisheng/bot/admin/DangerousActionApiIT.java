package com.tg.heyisheng.bot.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.admin.identity.AdminAccount;
import com.tg.heyisheng.bot.admin.identity.AdminAccountRepository;
import com.tg.heyisheng.bot.admin.identity.AdminRole;
import com.tg.heyisheng.bot.admin.identity.PasswordHasher;
import com.tg.heyisheng.bot.admin.system.RestartAction;
import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import com.tg.heyisheng.bot.core.platform.PlatformPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 危险动作双人复核端到端（模块十一 · 护栏）。
 *
 * <p>守的核心：<b>操作员单方面无法重启</b>——发起与批准是<b>两个不同主体</b>，
 * 且批准人必须是超管。这是"双人"的可观察定义。
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token"
})
@AutoConfigureMockMvc
class DangerousActionApiIT {

    private static final String SUPER = "it-dar-super";
    private static final String OPERATOR = "it-dar-op";
    private static final String PASSWORD = "it-pass-123";
    private static final AtomicBoolean RESTART_INVOKED = new AtomicBoolean(false);

    /** 记录替身：替代默认的「真退出」动作。 */
    @TestConfiguration
    static class RecordingRestart {
        @Bean
        @Primary
        RestartAction recordingRestartAction() {
            return () -> RESTART_INVOKED.set(true);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminAccountRepository accounts;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RuntimeConfigService configService;

    @Autowired
    private com.tg.heyisheng.bot.core.platform.PlatformGrantSource grants;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM config_override");
        jdbcTemplate.update("DELETE FROM dangerous_action_requests");
        configService.reload();
        RESTART_INVOKED.set(false);

        ensure(SUPER, AdminRole.SUPER_ADMIN);
        long operatorId = ensure(OPERATOR, AdminRole.OPERATOR);
        // 操作员持 SYSTEM_RESTART（只够「发起」，不够「批准」——批准恒需超管）
        if (!grants.hasPermission(ActorType.ADMIN_ACCOUNT, operatorId, PlatformPermission.SYSTEM_RESTART)) {
            grants.grant(ActorType.ADMIN_ACCOUNT, operatorId, PlatformPermission.SYSTEM_RESTART, null);
        }
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM config_override");
        jdbcTemplate.update("DELETE FROM dangerous_action_requests");
        configService.reload();
    }

    private long ensure(String username, AdminRole role) {
        return accounts.findByUsername(username).orElseGet(() ->
                accounts.save(new AdminAccount(username, PasswordHasher.hash(PASSWORD), role, Instant.now())))
                .getId();
    }

    private String login(String username) throws Exception {
        String json = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("token").asText();
    }

    private long operatorRequestsRestart(String operatorToken) throws Exception {
        String json = mockMvc.perform(post("/admin/dangerous-actions")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"RESTART\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("requestId").asLong();
    }

    @Test
    void operatorCannotRestartDirectly() throws Exception {
        // 收紧后：非超管的直接重启入口一律 403，必须走复核
        mockMvc.perform(post("/admin/system/restart").header("Authorization", "Bearer " + login(OPERATOR)))
                .andExpect(status().isForbidden());
        assertThat(RESTART_INVOKED).isFalse();
    }

    @Test
    void operatorRequestIsNotExecutedUntilApproved() throws Exception {
        long requestId = operatorRequestsRestart(login(OPERATOR));

        // 仅仅发起，动作尚未发生
        assertThat(RESTART_INVOKED).as("发起不等于执行").isFalse();
        assertThat(requestId).isPositive();
    }

    @Test
    void operatorCannotApproveItsOwnRequest() throws Exception {
        String operatorToken = login(OPERATOR);
        long requestId = operatorRequestsRestart(operatorToken);

        // 操作员既非超管，批准入口直接 403（「批准恒需超管」）
        mockMvc.perform(post("/admin/dangerous-actions/" + requestId + "/approve")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isForbidden());

        assertThat(RESTART_INVOKED).as("未获批准不得执行").isFalse();
    }

    @Test
    void superAdminApprovalExecutesTheAction() throws Exception {
        long requestId = operatorRequestsRestart(login(OPERATOR));

        // 开启重启门控（热参数）
        configService.set("tgg.admin.restart-enabled", "true", 0L);

        mockMvc.perform(post("/admin/dangerous-actions/" + requestId + "/approve")
                        .header("Authorization", "Bearer " + login(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPROVED"));

        assertThat(RESTART_INVOKED).as("批准才是真正触发动作的那一步").isTrue();
    }

    @Test
    void approvalIsIdempotent() throws Exception {
        long requestId = operatorRequestsRestart(login(OPERATOR));
        configService.set("tgg.admin.restart-enabled", "true", 0L);
        String superToken = login(SUPER);

        mockMvc.perform(post("/admin/dangerous-actions/" + requestId + "/approve")
                .header("Authorization", "Bearer " + superToken)).andExpect(status().isOk());

        // 二次批准：已有终态，不得重复执行
        mockMvc.perform(post("/admin/dangerous-actions/" + requestId + "/approve")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(status().isConflict());
    }

    @Test
    void approvalWithoutRestartEnabledIsRejectedAndLeavesRequestPending() throws Exception {
        long requestId = operatorRequestsRestart(login(OPERATOR));

        // 未开启 restart-enabled：批准被拒（409），且**不得**把请求推成终态——
        // 否则会出现「状态已变、审计记成功，动作却没发生」的假成功，此后 409 再也批不了。
        mockMvc.perform(post("/admin/dangerous-actions/" + requestId + "/approve")
                        .header("Authorization", "Bearer " + login(SUPER)))
                .andExpect(status().isConflict());

        assertThat(RESTART_INVOKED).as("部署方关掉重启开关即不接受经 Web 重启").isFalse();

        // 开关开启后，原请求仍是 PENDING，可再批
        configService.set("tgg.admin.restart-enabled", "true", 0L);
        mockMvc.perform(post("/admin/dangerous-actions/" + requestId + "/approve")
                        .header("Authorization", "Bearer " + login(SUPER)))
                .andExpect(status().isOk());
        assertThat(RESTART_INVOKED).as("开关开启后同一请求仍可批准").isTrue();
    }
}

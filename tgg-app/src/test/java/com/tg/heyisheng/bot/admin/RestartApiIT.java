package com.tg.heyisheng.bot.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.admin.identity.AdminAccount;
import com.tg.heyisheng.bot.admin.identity.AdminAccountRepository;
import com.tg.heyisheng.bot.admin.identity.AdminRole;
import com.tg.heyisheng.bot.admin.identity.PasswordHasher;
import com.tg.heyisheng.bot.admin.system.RestartAction;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
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
 * 重启端点端到端（模块十一 扩展）。
 *
 * <p><b>测试内绝不真退出</b>：把 {@link RestartAction} 换成记录替身（{@code @Primary}）。
 *
 * <p>权限：<b>超管天然全权</b>；非超管走配置写白名单。本测试用两个账号区分
 * ——超管可重启，操作员（非超管、不在任意白名单）被拒。
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token"
})
@AutoConfigureMockMvc
class RestartApiIT {

    private static final String SUPER = "it-restart-super";
    private static final String OPERATOR = "it-restart-operator";
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

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM config_override");
        configService.reload();
        RESTART_INVOKED.set(false);
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

    private org.springframework.test.web.servlet.ResultActions postRestart(String token) throws Exception {
        return mockMvc.perform(post("/admin/system/restart")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    void operatorWithoutWritePermissionIsForbidden() throws Exception {
        postRestart(login(OPERATOR)).andExpect(status().isForbidden());
        assertThat(RESTART_INVOKED).as("被拒的请求不得触发重启").isFalse();
    }

    @Test
    void missingSessionIsUnauthorized() throws Exception {
        mockMvc.perform(post("/admin/system/restart")).andExpect(status().isUnauthorized());
    }

    @Test
    void disabledByDefaultIsConflict() throws Exception {
        postRestart(login(SUPER)).andExpect(status().isConflict());
        assertThat(RESTART_INVOKED).as("未启用时不得触发重启").isFalse();
    }

    @Test
    void enabledRestartIsAcceptedAndTriggersAction() throws Exception {
        // 开关是热的：写覆盖后即时生效，无需重启
        configService.set("tgg.admin.restart-enabled", "true", 0L);

        postRestart(login(SUPER))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("RESTARTING"));

        assertThat(RESTART_INVOKED).as("放行时必须真的触发了重启动作").isTrue();
    }
}

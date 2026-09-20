package com.tg.heyisheng.bot.admin;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 重启端点端到端（模块十一 扩展）。
 *
 * <p><b>测试内绝不真退出</b>：把 {@link RestartAction} 换成记录替身（{@code @Primary}），
 * 于是可以断言「权限与开关都放行时才触发」——而 JVM 安然无恙。这是它能在 CI 跑的前提。
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token",
        "tgg.moderation.reviewers=777,888",
        "tgg.admin.config-admins=777"
})
@AutoConfigureMockMvc
class RestartApiIT {

    private static final String TOKEN = "Bearer test-admin-token";
    private static final long CONFIG_ADMIN = 777L;
    private static final long REVIEWER_ONLY = 888L;
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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RuntimeConfigService configService;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM config_override");
        configService.reload();
        RESTART_INVOKED.set(false);
    }

    private org.springframework.test.web.servlet.ResultActions postRestart(long operator) throws Exception {
        return mockMvc.perform(post("/admin/system/restart")
                .header("Authorization", TOKEN)
                .header("X-Operator-Id", String.valueOf(operator)));
    }

    @Test
    void reviewerWithoutWritePermissionIsForbidden() throws Exception {
        postRestart(REVIEWER_ONLY).andExpect(status().isForbidden());
        assertThat(RESTART_INVOKED).as("被拒的请求不得触发重启").isFalse();
    }

    @Test
    void disabledByDefaultIsConflict() throws Exception {
        postRestart(CONFIG_ADMIN).andExpect(status().isConflict());
        assertThat(RESTART_INVOKED).as("未启用时不得触发重启").isFalse();
    }

    @Test
    void enabledRestartIsAcceptedAndTriggersAction() throws Exception {
        // 开关是热的：写覆盖后即时生效，无需重启
        configService.set("tgg.admin.restart-enabled", "true", CONFIG_ADMIN);

        postRestart(CONFIG_ADMIN)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("RESTARTING"));

        assertThat(RESTART_INVOKED).as("放行时必须真的触发了重启动作").isTrue();
    }
}

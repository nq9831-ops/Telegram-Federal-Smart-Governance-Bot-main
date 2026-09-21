package com.tg.heyisheng.bot.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.admin.identity.AdminAccount;
import com.tg.heyisheng.bot.admin.identity.AdminAccountRepository;
import com.tg.heyisheng.bot.admin.identity.AdminRole;
import com.tg.heyisheng.bot.admin.identity.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账号管理端到端（模块十一 · 权限模型）。
 *
 * <p>守的关键闭环：<b>超管建操作员 → 授予 CONFIG_WRITE → 操作员凭该能力真的能改配置</b>；
 * 以及反向：<b>非超管不能建账号</b>（堵死「操作员给自己加权限」这条提权路）。
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token"
})
@AutoConfigureMockMvc
class AccountAdminApiIT {

    private static final String SUPER = "it-acct-super";
    private static final String PASSWORD = "it-pass-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminAccountRepository accounts;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        if (accounts.findByUsername(SUPER).isEmpty()) {
            accounts.save(new AdminAccount(SUPER, PasswordHasher.hash(PASSWORD),
                    AdminRole.SUPER_ADMIN, Instant.now()));
        }
    }

    @AfterEach
    void cleanup() {
        // 配置覆盖与授权账本会跨上下文泄漏——用后即清
        jdbcTemplate.update("DELETE FROM config_override");
    }

    private String login(String username, String password) throws Exception {
        String json = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("token").asText();
    }

    /** 用超管建一个操作员，返回其账号 id。用户名唯一以避免跨次运行冲突。 */
    private long createOperator(String superToken, String username, String password) throws Exception {
        String created = mockMvc.perform(post("/admin/accounts")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asLong();
    }

    @Test
    void missingSessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/accounts")).andExpect(status().isUnauthorized());
    }

    @Test
    void superAdminGrantsPermissionAndOperatorUsesIt() throws Exception {
        String superToken = login(SUPER, PASSWORD);
        String opName = "it-acct-op-" + System.nanoTime();
        long opId = createOperator(superToken, opName, "oppw");

        mockMvc.perform(put("/admin/accounts/" + opId + "/permissions")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"CONFIG_WRITE\"]}"))
                .andExpect(status().isOk());

        // 操作员登录后凭 CONFIG_WRITE 真的能改配置
        String opToken = login(opName, "oppw");
        mockMvc.perform(put("/admin/config/tgg.admin.overdue-remind-hours")
                        .header("Authorization", "Bearer " + opToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"7\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void operatorWithoutGrantCannotWriteConfig() throws Exception {
        String superToken = login(SUPER, PASSWORD);
        String opName = "it-acct-nogrant-" + System.nanoTime();
        createOperator(superToken, opName, "oppw");

        String opToken = login(opName, "oppw");
        mockMvc.perform(put("/admin/config/tgg.admin.overdue-remind-hours")
                        .header("Authorization", "Bearer " + opToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"7\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorCannotManageAccounts() throws Exception {
        String superToken = login(SUPER, PASSWORD);
        String opName = "it-acct-mgr-" + System.nanoTime();
        createOperator(superToken, opName, "oppw");

        String opToken = login(opName, "oppw");
        mockMvc.perform(post("/admin/accounts")
                        .header("Authorization", "Bearer " + opToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"evil\",\"password\":\"x\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/accounts").header("Authorization", "Bearer " + opToken))
                .andExpect(status().isForbidden());
    }
}

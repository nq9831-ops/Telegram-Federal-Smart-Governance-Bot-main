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
 * 后台认证端点端到端（模块十一 · 账号与权限模型）。
 *
 * <p>守的要点：① 登录成功返回会话令牌与主体（类型 ADMIN_ACCOUNT）；② 凭据错误一律 401、不泄露成因；
 * ③ <b>登出即失效</b>（服务端会话可即时吊销，这是它相对 JWT 的价值）；④ login 路径无需会话，
 * 其余 {@code /admin/*} 一律要会话。
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token"
})
@AutoConfigureMockMvc
class AdminAuthApiIT {

    private static final String USERNAME = "it-auth-user";
    private static final String PASSWORD = "s3cret-pass";

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

    private String token() throws Exception {
        String json = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("token").asText();
    }

    @Test
    void loginSucceedsAndReturnsTokenAndSubject() throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.subjectType").value("ADMIN_ACCOUNT"))
                .andExpect(jsonPath("$.role").value("SUPER_ADMIN"));
    }

    @Test
    void wrongPasswordIsUnauthorized() throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUserIsUnauthorized() throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost-user\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsCurrentSubject() throws Exception {
        mockMvc.perform(get("/admin/auth/me").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectType").value("ADMIN_ACCOUNT"))
                .andExpect(jsonPath("$.role").value("SUPER_ADMIN"));
    }

    @Test
    void logoutRevokesTheSessionImmediately() throws Exception {
        String token = token();

        mockMvc.perform(post("/admin/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 登出后原令牌不再可用——服务端会话的即时吊销
        mockMvc.perform(get("/admin/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}

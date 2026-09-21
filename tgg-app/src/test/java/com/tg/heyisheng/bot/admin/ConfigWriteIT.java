package com.tg.heyisheng.bot.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.admin.identity.AdminAccount;
import com.tg.heyisheng.bot.admin.identity.AdminAccountRepository;
import com.tg.heyisheng.bot.admin.identity.AdminRole;
import com.tg.heyisheng.bot.admin.identity.PasswordHasher;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 配置中心写侧端到端（模块十一 扩展）——真实上下文 + 真实 MySQL。
 *
 * <p>鉴权已改为服务端会话：超管天然全权；操作员（非超管、不在配置写白名单）写被拒 403。
 *
 * <p>覆盖反证：无写权限拒绝、热参数免重启即生效、装配开关改完不重启不生效、
 * 非法值拒绝、键不可写拒绝。
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token",
        // 白名单用一个不会被账号自增 id 撞上的值——确保操作员必定「不在名单内」
        "tgg.admin.config-admins=999999"
})
@AutoConfigureMockMvc
class ConfigWriteIT {

    private static final String SUPER = "it-cfgw-super";
    private static final String OPERATOR = "it-cfgw-operator";
    private static final String PASSWORD = "it-pass-123";
    private static final String HOT_KEY = "tgg.admin.overdue-remind-hours";

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
    private ApplicationContext context;

    /** 清覆盖并重载缓存——否则上一个用例写进 DB/缓存的覆盖会污染下一个。 */
    @BeforeEach
    void resetOverrides() {
        jdbcTemplate.update("DELETE FROM config_override");
        configService.reload();
        ensure(SUPER, AdminRole.SUPER_ADMIN);
        ensure(OPERATOR, AdminRole.OPERATOR);
    }

    /**
     * 收尾清理同样重要：{@code ConfigOverrideEnvironmentPostProcessor} 会在**每个**上下文启动时读这张表，
     * 若本类留下的覆盖行被别的测试上下文读到，会静默改变它们的装配——故用后即清。
     */
    @AfterEach
    void cleanupOverrides() {
        jdbcTemplate.update("DELETE FROM config_override");
        configService.reload();
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

    private static String body(String value) {
        return "{\"value\":\"" + value + "\"}";
    }

    private org.springframework.test.web.servlet.ResultActions putAs(String token, String key, String value)
            throws Exception {
        return mockMvc.perform(put("/admin/config/" + key)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(value)));
    }

    private String readOverview() throws Exception {
        return mockMvc.perform(get("/admin/config").header("Authorization", "Bearer " + login(SUPER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ───────────────────────────── 反证：无写权限 ─────────────────────────────

    @Test
    void operatorWithoutWritePermissionIsForbidden() throws Exception {
        putAs(login(OPERATOR), HOT_KEY, "6").andExpect(status().isForbidden());

        // 且不得有任何落库/生效
        assertThat(readOverview()).contains("\"effectiveValue\":\"24\"");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM config_override", Integer.class))
                .isZero();
    }

    // ───────────────────────── 热参数免重启即生效 ─────────────────────────

    @Test
    void superAdminCanWriteHotParamAndItTakesEffectWithoutRestart() throws Exception {
        putAs(login(SUPER), HOT_KEY, "6")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("UPDATED"));

        // 无需重启：总览与消费方读取的服务都立即看到新值
        assertThat(readOverview()).contains("\"effectiveValue\":\"6\"");
        assertThat(configService.getInt(HOT_KEY, 24)).isEqualTo(6);
    }

    @Test
    void clearRevertsToDefault() throws Exception {
        putAs(login(SUPER), HOT_KEY, "6").andExpect(status().isOk());

        mockMvc.perform(delete("/admin/config/" + HOT_KEY)
                        .header("Authorization", "Bearer " + login(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("CLEARED"));

        assertThat(configService.getInt(HOT_KEY, 24)).isEqualTo(24);
    }

    /** 审计必须能回答「原来是多少」——只记键名在事故回溯时等于没记。 */
    @Test
    void writeIsAuditedWithOldAndNewValues() throws Exception {
        putAs(login(SUPER), HOT_KEY, "6").andExpect(status().isOk());

        String detail = jdbcTemplate.queryForObject(
                "SELECT detail FROM audit_log WHERE action = 'admin.config.update' "
                        + "ORDER BY id DESC LIMIT 1", String.class);

        assertThat(detail).as("审计明细应含旧值与新值").contains("old=24").contains("new=6");
    }

    /** 写权限名单来源要能被界面读到（本测试显式配了 tgg.admin.config-admins）。 */
    @Test
    void writePermissionSourceIsExposed() throws Exception {
        String body = mockMvc.perform(get("/admin/config/permissions")
                        .header("Authorization", "Bearer " + login(SUPER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).as("本测试显式配了 tgg.admin.config-admins，来源应为 explicit")
                .contains("\"source\":\"explicit\"");
    }

    // ───────────────────────── 装配开关改完不重启不生效 ─────────────────────────

    @Test
    void assemblySwitchHasNoEffectWithoutRestart() throws Exception {
        assertThat(context.getBeansOfType(com.tg.heyisheng.bot.listing.ListingGroupService.class))
                .as("前置：测试上下文里 listing 默认关闭").isEmpty();

        putAs(login(SUPER), "tgg.listing.enabled", "true").andExpect(status().isOk());

        assertThat(context.getBeansOfType(com.tg.heyisheng.bot.listing.ListingGroupService.class))
                .as("装配开关经 Web 改动**不会**立即生效——必须重启")
                .isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT config_value FROM config_override WHERE config_key = 'tgg.listing.enabled'",
                String.class)).isEqualTo("true");
    }

    // ───────────────────────── 非法值与被拒分类 ─────────────────────────

    @Test
    void invalidValueIsBadRequestAndNotPersisted() throws Exception {
        putAs(login(SUPER), HOT_KEY, "-5").andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM config_override", Integer.class))
                .isZero();
    }

    @Test
    void enablingAssemblySwitchWithoutPrerequisiteIsRejected() throws Exception {
        putAs(login(SUPER), "tgg.credit.enabled", "true")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("tgg.credit.private-key")));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM config_override", Integer.class))
                .isZero();
    }

    @Test
    void unknownKeyIsNotFound() throws Exception {
        putAs(login(SUPER), "tgg.nope", "1").andExpect(status().isNotFound());
    }

    @Test
    void secretKeyIsNotWritable() throws Exception {
        putAs(login(SUPER), "tgg.webhook.secret", "leak")
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM config_override", Integer.class))
                .isZero();
    }
}

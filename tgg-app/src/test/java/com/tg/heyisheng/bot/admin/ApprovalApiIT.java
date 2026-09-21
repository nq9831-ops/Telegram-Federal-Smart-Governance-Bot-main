package com.tg.heyisheng.bot.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.admin.identity.AdminAccount;
import com.tg.heyisheng.bot.admin.identity.AdminAccountRepository;
import com.tg.heyisheng.bot.admin.identity.AdminRole;
import com.tg.heyisheng.bot.admin.identity.PasswordHasher;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewItem;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository;
import com.tg.heyisheng.bot.core.moderation.ReviewStatus;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审批中心端到端（模块十一）——<b>真实 Spring 上下文 + 真实 MySQL</b>。
 *
 * <p><b>鉴权已改为服务端会话</b>：先经 {@code POST /admin/auth/login} 拿会话令牌，
 * 再以 {@code Authorization: Bearer <令牌>} 访问。请求头里的 {@code X-Operator-Id} 已彻底移除——
 * 身份不再可伪造。
 *
 * <p><b>两个靠 IT 才能守的点</b>：① 404 必须真是 404（全局异常处理器会吞成 200）；
 * ② 裁决的审计主体必须记成 {@code ADMIN_ACCOUNT}（后台主体），而非 TG 用户。
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token"
})
@AutoConfigureMockMvc
class ApprovalApiIT {

    private static final long CHAT = -1002000000009L;
    private static final long OFFENDER = 888L;
    private static final String USERNAME = "it-approval-super";
    private static final String PASSWORD = "it-pass-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ModerationReviewRepository repository;

    @Autowired
    private AdminAccountRepository accounts;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        if (accounts.findByUsername(USERNAME).isEmpty()) {
            accounts.save(new AdminAccount(USERNAME, PasswordHasher.hash(PASSWORD),
                    AdminRole.SUPER_ADMIN, Instant.now()));
        }
    }

    /** 登录并返回会话令牌。 */
    private String login() throws Exception {
        String json = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("token").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private long seed(RiskLevel level, boolean hardLine, Long subject) {
        return repository.save(new ModerationReviewItem(CHAT, subject, 1,
                List.of("HARD_CSAM"), level, hardLine)).getId();
    }

    private static String body(String decision) {
        return "{\"decision\":\"" + decision + "\",\"reason\":\"人工复核结论\"}";
    }

    @Test
    void missingSessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/approvals")).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidSessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/approvals").header("Authorization", "Bearer not-a-session"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listPutsHardLineFirstEvenWhenItIsOlder() throws Exception {
        long plainHigh = seed(RiskLevel.HIGH, false, OFFENDER);
        long hardLine = seed(RiskLevel.LOW, true, OFFENDER);

        mockMvc.perform(get("/admin/approvals").header("Authorization", bearer(login())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value((int) hardLine))
                .andExpect(jsonPath("$.items[1].id").value((int) plainHigh));
    }

    @Test
    void detailReturnsRealNotFoundForUnknownId() throws Exception {
        mockMvc.perform(get("/admin/approvals/999999").header("Authorization", bearer(login())))
                .andExpect(status().isNotFound());
    }

    @Test
    void decideApprovesAndPersistsTheConclusion() throws Exception {
        long id = seed(RiskLevel.MEDIUM, false, OFFENDER);

        mockMvc.perform(post("/admin/approvals/" + id + "/decide")
                        .header("Authorization", bearer(login()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("APPROVED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DECIDED"))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        ModerationReviewItem stored = repository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(stored.getDecidedAt()).isNotNull();
    }

    /** 裁决的审计主体必须记成 ADMIN_ACCOUNT（后台账号），不是 TG 用户。 */
    @Test
    void decideIsAuditedAsAdminAccount() throws Exception {
        long id = seed(RiskLevel.MEDIUM, false, OFFENDER);

        mockMvc.perform(post("/admin/approvals/" + id + "/decide")
                        .header("Authorization", bearer(login()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("APPROVED")))
                .andExpect(status().isOk());

        List<String> types = jdbcTemplate.queryForList(
                "SELECT actor_type FROM audit_log WHERE action = 'admin.approval.decide' AND target = ?",
                String.class, id);
        assertThat(types).as("后台裁决的审计主体应为 ADMIN_ACCOUNT").contains("ADMIN_ACCOUNT");
    }

    @Test
    void decideIsIdempotentOnTheSecondCall() throws Exception {
        long id = seed(RiskLevel.LOW, false, OFFENDER);
        String token = login();

        mockMvc.perform(post("/admin/approvals/" + id + "/decide")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body("REJECTED"))).andExpect(status().isOk());

        mockMvc.perform(post("/admin/approvals/" + id + "/decide")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body("APPROVED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ALREADY_DECIDED"))
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(repository.findById(id).orElseThrow().getStatus())
                .as("重复裁决不得翻转既有结论")
                .isEqualTo(ReviewStatus.REJECTED);
    }

    @Test
    void invalidDecisionIsRejectedWithBadRequest() throws Exception {
        long id = seed(RiskLevel.LOW, false, OFFENDER);

        mockMvc.perform(post("/admin/approvals/" + id + "/decide")
                        .header("Authorization", bearer(login()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("DELETE")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statsCountsByStatusAndOverdue() throws Exception {
        long pendingId = seed(RiskLevel.HIGH, true, OFFENDER);
        long decidedId = seed(RiskLevel.LOW, false, OFFENDER);
        jdbcTemplate.update("UPDATE moderation_review_queue SET created_at = "
                + "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 100 HOUR) WHERE id = ?", pendingId);
        // 注意：必须 save **被改过的那个实例**。
        ModerationReviewItem decided = repository.findById(decidedId).orElseThrow();
        decided.decide(ReviewStatus.APPROVED, 1L, "ok", java.time.Instant.now());
        repository.save(decided);

        mockMvc.perform(get("/admin/approvals/stats").header("Authorization", bearer(login())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.approved").value(1))
                .andExpect(jsonPath("$.hardLinePending").value(1))
                .andExpect(jsonPath("$.overdueRemind").value(1))
                .andExpect(jsonPath("$.overdueEscalate").value(1));
    }
}

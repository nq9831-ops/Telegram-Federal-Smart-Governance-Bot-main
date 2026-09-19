package com.tg.heyisheng.bot.admin;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审批中心端到端（模块十一）——<b>真实 Spring 上下文 + 真实 MySQL</b>，不 mock 中间层。
 *
 * <p>为什么要到端到端这一层：门禁在 {@code Filter}、裁决在 core 服务、存储在两处表里，
 * 各自单测都绿并不能说明「带上 token 打进来能裁决成功」。
 *
 * <p><b>两个务必靠 IT 才能守住的点</b>：
 * <ol>
 *   <li><b>404 必须真是 404</b>：本项目有全局 {@code @RestControllerAdvice} 把**业务异常**统一吞成 200
 *       （为 Telegram 避免重试风暴的既有设计）。若控制器靠抛异常表达 404，
 *       调用方会拿到 200 空体——这条只有端到端能发现。</li>
 *   <li><b>「不可自审」要在真实白名单下成立</b>：888 既是被判定者又在复核人白名单内，
 *       于是它能过门禁、却必须在裁决前被拦下。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token",
        "tgg.moderation.reviewers=777,888"
})
@AutoConfigureMockMvc
class ApprovalApiIT {

    private static final String TOKEN = "Bearer test-admin-token";
    private static final long REVIEWER = 777L;
    private static final long OFFENDER = 888L;
    private static final long OUTSIDER = 999L;
    private static final long CHAT = -1002000000009L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ModerationReviewRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    private long seed(RiskLevel level, boolean hardLine, Long subject) {
        return repository.save(new ModerationReviewItem(CHAT, subject, 1,
                List.of("HARD_CSAM"), level, hardLine)).getId();
    }

    private static String body(String decision) {
        return "{\"decision\":\"" + decision + "\",\"reason\":\"人工复核结论\"}";
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/approvals"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/approvals").header("Authorization", "Bearer wrong")
                        .header("X-Operator-Id", String.valueOf(REVIEWER)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonWhitelistedOperatorIsForbidden() throws Exception {
        mockMvc.perform(get("/admin/approvals").header("Authorization", TOKEN)
                        .header("X-Operator-Id", String.valueOf(OUTSIDER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void listPutsHardLineFirstEvenWhenItIsOlder() throws Exception {
        long plainHigh = seed(RiskLevel.HIGH, false, OFFENDER);
        long hardLine = seed(RiskLevel.LOW, true, OFFENDER);

        mockMvc.perform(get("/admin/approvals").header("Authorization", TOKEN)
                        .header("X-Operator-Id", String.valueOf(REVIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value((int) hardLine))
                .andExpect(jsonPath("$.items[1].id").value((int) plainHigh));
    }

    @Test
    void detailReturnsRealNotFoundForUnknownId() throws Exception {
        mockMvc.perform(get("/admin/approvals/999999").header("Authorization", TOKEN)
                        .header("X-Operator-Id", String.valueOf(REVIEWER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void decideIsForbiddenWhenTheOperatorIsTheSubject() throws Exception {
        long id = seed(RiskLevel.HIGH, true, OFFENDER);

        mockMvc.perform(post("/admin/approvals/" + id + "/decide")
                        .header("Authorization", TOKEN)
                        .header("X-Operator-Id", String.valueOf(OFFENDER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REJECTED")))
                .andExpect(status().isForbidden());

        assertThat(repository.findById(id).orElseThrow().getStatus())
                .as("被拒的自审不得改变任何状态")
                .isEqualTo(ReviewStatus.PENDING);
    }

    @Test
    void decideApprovesAndPersistsTheConclusion() throws Exception {
        long id = seed(RiskLevel.MEDIUM, false, OFFENDER);

        mockMvc.perform(post("/admin/approvals/" + id + "/decide")
                        .header("Authorization", TOKEN)
                        .header("X-Operator-Id", String.valueOf(REVIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("APPROVED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DECIDED"))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        ModerationReviewItem stored = repository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(stored.getDecidedBy()).isEqualTo(REVIEWER);
        assertThat(stored.getDecidedAt()).isNotNull();
    }

    @Test
    void decideIsIdempotentOnTheSecondCall() throws Exception {
        long id = seed(RiskLevel.LOW, false, OFFENDER);

        mockMvc.perform(post("/admin/approvals/" + id + "/decide")
                .header("Authorization", TOKEN).header("X-Operator-Id", String.valueOf(REVIEWER))
                .contentType(MediaType.APPLICATION_JSON).content(body("REJECTED"))).andExpect(status().isOk());

        mockMvc.perform(post("/admin/approvals/" + id + "/decide")
                        .header("Authorization", TOKEN).header("X-Operator-Id", String.valueOf(REVIEWER))
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
                        .header("Authorization", TOKEN).header("X-Operator-Id", String.valueOf(REVIEWER))
                        .contentType(MediaType.APPLICATION_JSON).content(body("DELETE")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statsCountsByStatusAndOverdue() throws Exception {
        long pendingId = seed(RiskLevel.HIGH, true, OFFENDER);
        long decidedId = seed(RiskLevel.LOW, false, OFFENDER);
        // 把一条的入队时间推到 100 小时前——超过 24h 提醒与 72h 升级两个阈值
        jdbcTemplate.update("UPDATE moderation_review_queue SET created_at = "
                + "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 100 HOUR) WHERE id = ?", pendingId);
        // 注意：必须 save **被改过的那个实例**。上一版写成「改游离实体、再 save 重新查出来的一个」，
        // 等于什么都没保存（于是 pending 多算了 1 条）——测试自己的 bug，不是产品缺陷。
        ModerationReviewItem decided = repository.findById(decidedId).orElseThrow();
        decided.decide(ReviewStatus.APPROVED, REVIEWER, "ok", java.time.Instant.now());
        repository.save(decided);

        mockMvc.perform(get("/admin/approvals/stats").header("Authorization", TOKEN)
                        .header("X-Operator-Id", String.valueOf(REVIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.approved").value(1))
                .andExpect(jsonPath("$.hardLinePending").value(1))
                .andExpect(jsonPath("$.overdueRemind").value(1))
                .andExpect(jsonPath("$.overdueEscalate").value(1));
    }
}

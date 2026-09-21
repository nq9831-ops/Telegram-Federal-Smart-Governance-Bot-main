package com.tg.heyisheng.bot.credit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.admin.identity.AdminAccount;
import com.tg.heyisheng.bot.admin.identity.AdminAccountRepository;
import com.tg.heyisheng.bot.admin.identity.AdminRole;
import com.tg.heyisheng.bot.admin.identity.PasswordHasher;
import com.tg.heyisheng.bot.core.credit.CreditEvent;
import com.tg.heyisheng.bot.core.credit.CreditEventType;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewItem;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>用户级端到端</b>：复核人在后台推翻一条误判案件 → 当事人被误扣的信用分<b>真的退回来</b>。
 *
 * <p><b>为什么这一层不可省</b>：{@code CreditReversalIT} 证明的是 {@code reverseOf} 在真库上的行为，
 * 接线单测证明的是"推翻会调用 sink"——但"两条接在一起、且走真实 HTTP 与真实事务后，
 * 用户的分真的变了"只有这一层能证明。中间任何一处断链（bean 没装配、幂等键算错、
 * 事务回滚吞掉补偿）都只在端到端暴露。
 *
 * <p>不 mock 中间层：真实 Spring 上下文（模块七启用）+ 真实 MySQL + 真实 HTTP 裁决端点。
 */
@SpringBootTest(properties = {
        "tgg.credit.enabled=true",
        "tgg.admin.api-token=test-admin-token"
})
@AutoConfigureMockMvc
class CreditRefundEndToEndIT {

    private static final long CHAT = -100900777L;
    private static final long USER_ID = 880001L;
    private static final int MESSAGE_ID = 555;
    /** 与生产端同源的幂等键形态。 */
    private static final String ORIGINAL_KEY = "moderation:" + CHAT + ":" + MESSAGE_ID;
    private static final String REVERSAL_KEY = "moderation-reversal:" + CHAT + ":" + MESSAGE_ID;

    private static final String SUPER = "it-refund-super";
    private static final String PASSWORD = "it-pass-123";

    private static final KeyPair NODE_KEY = generateKeyPair();

    private static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void creditPrivateKey(DynamicPropertyRegistry registry) {
        registry.add("tgg.credit.private-key",
                () -> Base64.getEncoder().encodeToString(NODE_KEY.getPrivate().getEncoded()));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ModerationReviewRepository reviews;

    @Autowired
    private CreditService credit;

    @Autowired
    private CreditEventRecordRepository events;

    @Autowired
    private AdminAccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        reviews.deleteAll();
        jdbc.update("DELETE FROM credit_events WHERE subject_id = ?", USER_ID);
        jdbc.update("DELETE FROM credit_scores WHERE subject_id = ?", USER_ID);
        if (accounts.findByUsername(SUPER).isEmpty()) {
            accounts.save(new AdminAccount(SUPER, PasswordHasher.hash(PASSWORD),
                    AdminRole.SUPER_ADMIN, Instant.now()));
        }
    }

    private String loginSuperAdmin() throws Exception {
        String json = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + SUPER + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("token").asText();
    }

    @Test
    void rejectingACaseRefundsTheActuallyDeductedCredit() throws Exception {
        // ① 案件入队——与扣分事件同源（同一 chatId + messageId）
        long caseId = reviews.save(new ModerationReviewItem(CHAT, USER_ID, MESSAGE_ID,
                List.of("HARD_CSAM"), RiskLevel.HIGH, false)).getId();

        // ② 该次命中真的扣了分（走真实记账链路）
        credit.apply(CreditEvent.of(CreditSubjectType.INDIVIDUAL, USER_ID,
                CreditEventType.MODERATION_HIT, RiskLevel.HIGH, false, "moderation", false, ORIGINAL_KEY));
        assertThat(credit.scoreOf(CreditSubjectType.INDIVIDUAL, USER_ID))
                .as("前置：HIGH 命中扣 30").isEqualTo(70);

        // ③ 复核人经真实 HTTP 端点推翻（超管会话）
        mockMvc.perform(post("/admin/approvals/" + caseId + "/decide")
                        .header("Authorization", "Bearer " + loginSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECTED\",\"reason\":\"误报\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DECIDED"));

        // ④ 用户可观察的结果
        assertThat(credit.scoreOf(CreditSubjectType.INDIVIDUAL, USER_ID))
                .as("推翻后误扣的分应退回（100）").isEqualTo(100);
        assertThat(events.findByIdempotencyKey(REVERSAL_KEY))
                .as("流水里应有一条反向补偿记录")
                .isPresent()
                .get()
                .satisfies(record -> {
                    assertThat(record.getEventType()).isEqualTo(CreditEventType.MODERATION_REVERSAL);
                    assertThat(record.getScoreDelta()).as("补偿为正向").isEqualTo(30);
                });
        assertThat(events.findByIdempotencyKey(ORIGINAL_KEY))
                .as("原扣分流水不可被改写或删除（只追加）").isPresent();
    }
}

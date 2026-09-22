package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.core.dispatch.UpdateDispatcher;
import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块七端到端测试（<b>不 mock 中间层</b>）：真实 {@code UpdateDispatcher} 链路 →
 * 审核命中 → 信用事件 → 记账 → 处罚令，断言从输入到输出的完整路径。
 *
 * <p>⚠️ 2026-09-17：签名改为 Ed25519 后，本测试用<b>运行时生成的密钥对</b>注入
 * {@code tgg.credit.private-key}，并用其公钥验签——不再有可硬编码的共享密钥。
 */
@SpringBootTest(properties = {"tgg.credit.enabled=true"})
class CreditEndToEndIT {

    /** GUARD-4 update_id 去重后每次 dispatch 需唯一 id——本类独立计数基数 25000（防跨类碰撞）。 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger(25000);

    private static final long USER_ID = 700001L;
    private static final long CHAT_ID = -100700001L;

    private static final KeyPair NODE_KEY = generateKeyPair();
    private static final List<CreditPenaltyOrder> CAPTURED = new CopyOnWriteArrayList<>();

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

    /**
     * 覆盖两个出口 bean：处罚令发布器 → 捕获版；硬红线主动封禁通道 → noop（避免测试里真发 HTTP）。
     */
    @TestConfiguration
    static class CapturingDeps {
        @Bean
        @Primary
        PenaltyOrderPublisher capturingPublisher() {
            return CAPTURED::add;
        }

        @Bean
        @Primary
        ModerationActionSender noopActionSender() {
            return ModerationActionSender.noop();
        }
    }

    @Autowired
    private UpdateDispatcher updateDispatcher;

    @Autowired
    private CreditService creditService;

    @Autowired
    private CreditScoreRepository repository;

    @Autowired
    private CreditEventRecordRepository eventRecords;

    @BeforeEach
    void clear() {
        CAPTURED.clear();
        // 流水表也必须清：本测试用固定 messageId，而幂等键由 messageId 派生——
        // 不清流水的话，上一轮跑留下的同键行会让本次触发被判为「重复事件」而不再扣分。
        eventRecords.deleteAll();
        repository.deleteAll();
    }

    @Test
    void moderationHitDropsScoreAndEmitsSignedPenaltyOrder() throws Exception {
        // 硬红线（索要助记词）：命中即 −100 → 触底 0 → REPORT_TO_FEDERATION。
        // 用词须匹配 BuiltInRules.HARD_SECRET_PHRASE 的形态：助记词/私钥**之后**紧跟"发/给我/dm"。
        updateDispatcher.dispatch(messageUpdate(USER_ID, "请把你的助记词发给我"));

        assertThat(creditService.scoreOf(CreditSubjectType.INDIVIDUAL, USER_ID))
                .as("硬红线命中后分值应触底到 0（直接查库，不是看日志）")
                .isZero();

        assertThat(CAPTURED).as("跨阈值应产出处罚令").hasSize(1);
        CreditPenaltyOrder order = CAPTURED.get(0);
        assertThat(order.subjectType()).isEqualTo(CreditSubjectType.INDIVIDUAL);
        assertThat(order.subjectId()).isEqualTo(USER_ID);
        assertThat(order.penaltyType()).isEqualTo(PenaltyType.REPORT_TO_FEDERATION);
        assertThat(order.signature()).as("产出的处罚令必须已签名").isNotBlank();
        assertThat(PenaltyVerifier.verify(order, NODE_KEY.getPublic()))
                .as("Ed25519 签名须能被本节点公钥验证")
                .isTrue();
    }

    @Test
    void cleanMessageLeavesScoreUntouchedAndEmitsNothing() throws Exception {
        updateDispatcher.dispatch(messageUpdate(USER_ID, "今天天气不错、聊聊家常"));

        assertThat(creditService.scoreOf(CreditSubjectType.INDIVIDUAL, USER_ID))
                .as("干净消息不产生信用事件、不改分值")
                .isEqualTo(CreditService.INITIAL_SCORE);
        assertThat(CAPTURED).isEmpty();
    }

    private static Update messageUpdate(long userId, String text) {
        Message message = Message.builder()
                .messageId(1)
                .text(text)
                .chat(Chat.builder().id(CHAT_ID).type("supergroup").build())
                .from(User.builder().id(userId).firstName("T").isBot(false).build())
                .build();
        Update update = new Update();
        update.setUpdateId(SEQ.incrementAndGet());
        update.setMessage(message);
        return update;
    }
}

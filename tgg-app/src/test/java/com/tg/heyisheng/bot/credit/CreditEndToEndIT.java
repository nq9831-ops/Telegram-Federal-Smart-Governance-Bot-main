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
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块七端到端测试（<b>不 mock 中间层</b>）：真实 {@code UpdateDispatcher} 链路 →
 * 审核命中 → 信用事件 → 记账 → 处罚令，断言从输入到输出的完整路径。
 *
 * <p><b>验收口径</b>：断言"库中分值下降"与"捕获到已签名的处罚令"——
 * 而非"publish 被调用过"或"日志里有记录"（{@code LESSONS.md} 坑 2：调用次数/日志 ≠ 真实行为）。
 */
@SpringBootTest(properties = {
        "tgg.credit.enabled=true",
        "tgg.credit.signing-key=test-signing-key"
})
class CreditEndToEndIT {

    private static final long USER_ID = 700001L;
    private static final long CHAT_ID = -100700001L;

    private static final List<CreditPenaltyOrder> CAPTURED = new CopyOnWriteArrayList<>();

    /**
     * 覆盖两个出口 bean：
     * <ul>
     *   <li>处罚令发布器 → 捕获版（本阶段默认实现是 noop，捕获才能断言"产出过令"）；</li>
     *   <li>硬红线的主动封禁通道 → noop（避免测试里真发 HTTP 到 Telegram）。</li>
     * </ul>
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

    @BeforeEach
    void clear() {
        CAPTURED.clear();
        repository.deleteAll();
    }

    @Test
    void moderationHitDropsScoreAndEmitsSignedPenaltyOrder() throws Exception {
        // 硬红线（索要助记词）：命中即 −100 → 触底 0 → REPORT_TO_FEDERATION。
        // 注意用词须匹配 BuiltInRules.HARD_SECRET_PHRASE 的形态：助记词/私钥**之后**紧跟"发/给我/dm"。
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
        assertThat(new PenaltySigner("test-signing-key").verify(order))
                .as("签名须能被同一密钥验证")
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
        update.setUpdateId(1);
        update.setMessage(message);
        return update;
    }
}

package com.tg.heyisheng.bot.listing;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.core.dispatch.UpdateDispatcher;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.listing.merchant.Merchant;
import com.tg.heyisheng.bot.listing.merchant.MerchantRepository;
import com.tg.heyisheng.bot.listing.merchant.MerchantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.EntityType;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模块六端到端测试（真库 + 真实命令链路）：入驻申请 → 复核 → 入驻成功 → <b>商家信用分落账</b>。
 *
 * <p><b>为什么必须走真链路而不是只测 service</b>：
 * <ul>
 *   <li>命令注册表是「一个命令名 → 一个处理器 + 一个权限点」的严格映射——三个新命令能否与
 *       既有的 {@code /listing_*} 共存，只有以真实上下文档启动才能证明；</li>
 *   <li>{@code merchants} 表由 <b>V6</b> 建立而非新迁移，本测试是「实体与既有表结构一致」的守门人
 *       （{@code ddl-auto: validate} 只在启动时校验，真跑一遍写读才算数）。</li>
 * </ul>
 *
 * <p><b>信用分断言的是产物，不是调用</b>：直接 {@code SELECT score FROM credit_scores}
 * 取 {@code (MERCHANT, merchantId)} 行——数「{@code ensureInitialized} 被调用几次」证明不了
 * 分值真的落了库（LESSONS：断言必须触碰真实行为）。
 *
 * <p><b>降级路径也被覆盖</b>：非复核人执行 {@code /merchant_review} 必须静默（返回空回复）
 * 且状态不变——这是「全局白名单门控真的生效」的证据。
 */
@SpringBootTest(properties = {
        "tgg.merchant.enabled=true",
        // 商家信用分走模块七的账本，故两个开关都要开
        "tgg.credit.enabled=true",
        "tgg.merchant.reviewers=" + MerchantOnboardingIT.REVIEWER_USER,
        "tgg.merchant.initial-score=500"
})
class MerchantOnboardingIT {

    static final long REVIEWER_USER = 888001L;
    private static final long OWNER_USER = 888002L;
    private static final long OUTSIDER_USER = 888003L;
    private static final long CHAT_ID = -100900002L;
    private static final String MERCHANT_NAME = "测试小铺";

    /** 模块七的处罚令签名需要一个真实密钥对（Ed25519 私钥 Base64）。 */
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
    private UpdateDispatcher updateDispatcher;

    @Autowired
    private MerchantService merchants;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private GroupConfigService groupConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        clean();
        groupConfigService.setEnabled(CHAT_ID, true);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM credit_scores WHERE subject_type = 'MERCHANT'");
        merchantRepository.deleteAll();
    }

    /**
     * 主链路：申请 → 复核通过 → 缴费 → 入驻成功 → 信用分产物 == 500。
     */
    @Test
    void onboardingUpToActiveInitializesMerchantCreditWithConfiguredScore() throws Exception {
        // 1. 提交入驻申请（经真实命令链路：注册表 → 权限 → 处理器）
        assertThat(replyTo("/merchant_apply " + MERCHANT_NAME, OWNER_USER))
                .as("首次申请应新建条目")
                .startsWith("入驻申请已提交（编号 #");

        List<Merchant> all = merchantRepository.findByOwnerUserIdOrderByIdAsc(OWNER_USER);
        assertThat(all).hasSize(1);
        Merchant merchant = all.get(0);
        long merchantId = merchant.getId();
        assertThat(merchant.getStatus()).isEqualTo(Merchant.Status.SUBMITTED.name());

        // 2. 重复申请被拒绝并回显既有编号（不得产出第二条并行申请）
        assertThat(replyTo("/merchant_apply 第二家店", OWNER_USER))
                .as("已有在办申请时应拒绝重复提交")
                .isEqualTo("你已有在办的入驻申请（编号 #" + merchantId + "），请等待复核。");
        assertThat(merchantRepository.findByOwnerUserIdOrderByIdAsc(OWNER_USER)).hasSize(1);

        // 3. 非复核人复核：静默（空回复）、状态不得变化
        assertThat(updateDispatcher.dispatch(update("/merchant_review " + merchantId + " approve", OUTSIDER_USER)))
                .as("非复核人应被静默拒绝（不回复，避免暴露命令存在）")
                .isEmpty();
        assertThat(merchantRepository.findById(merchantId).orElseThrow().getStatus())
                .as("越权复核不得改动状态")
                .isEqualTo(Merchant.Status.SUBMITTED.name());

        // 4. 复核人批准
        assertThat(replyTo("/merchant_review " + merchantId + " approve", REVIEWER_USER))
                .isEqualTo("商家 #" + merchantId + " 复核结论已写入：APPROVED。");
        assertThat(merchantRepository.findById(merchantId).orElseThrow().getStatus())
                .isEqualTo(Merchant.Status.APPROVED.name());

        // 5. 缴费并入驻成功（保证金流程属 Wave 5，此处直接驱动状态机）
        assertThat(merchants.markDepositPending(merchantId)).isPresent();
        assertThat(merchants.markActive(merchantId)).isPresent();
        assertThat(merchantRepository.findById(merchantId).orElseThrow().getStatus())
                .isEqualTo(Merchant.Status.ACTIVE.name());

        // 6. 断言产物：credit_scores 的 (MERCHANT, merchantId) 行分值 == 500
        Integer score = jdbcTemplate.queryForObject(
                "SELECT score FROM credit_scores WHERE subject_type = 'MERCHANT' AND subject_id = ?",
                Integer.class, merchantId);
        assertThat(score)
                .as("入驻成功必须真的在账本上落 500 分（不是调用了一次）")
                .isEqualTo(500);

        // 7. 再 markActive 一次：状态机拒绝（已 ACTIVE），分值不得被重置
        assertThatThrownBy(() -> merchants.markActive(merchantId))
                .as("已 ACTIVE 的商家再 markActive 应被状态机拒绝（而不是静默改状态）")
                .isInstanceOf(TggException.class);
        Integer scoreAgain = jdbcTemplate.queryForObject(
                "SELECT score FROM credit_scores WHERE subject_type = 'MERCHANT' AND subject_id = ?",
                Integer.class, merchantId);
        assertThat(scoreAgain).as("被拒绝的迁移不得触碰账本").isEqualTo(500);
    }

    /** 状态查询命令：未申请过时给出明确提示，已申请时回显本人最新状态。 */
    @Test
    void statusCommandReportsOwnLatestApplication() throws Exception {
        assertThat(replyTo("/merchant_status", OWNER_USER))
                .as("未申请过时应明确告知，而不是静默")
                .contains("还没有提交过商家入驻申请");

        replyTo("/merchant_apply " + MERCHANT_NAME, OWNER_USER);
        assertThat(replyTo("/merchant_status", OWNER_USER))
                .contains("测试小铺")
                .contains("已提交，等待资质复核");
    }

    /** 复核参数非法时给出用法提示（不得让实体状态机异常穿透到分发层）。 */
    @Test
    void malformedReviewArgumentsYieldUsageHint() throws Exception {
        assertThat(replyTo("/merchant_review", REVIEWER_USER)).isEqualTo(
                "用法：/merchant_review 商家编号 结论\n"
                        + "例：/merchant_review 1 approve\n"
                        + "结论可为 approve（通过）/ reject（驳回）/ need-more（要求补充材料）。");
        assertThat(replyTo("/merchant_review 999 approve", REVIEWER_USER)).isEqualTo("未找到商家编号 999。");

        replyTo("/merchant_apply " + MERCHANT_NAME, OWNER_USER);
        long merchantId = merchantRepository.findByOwnerUserIdOrderByIdAsc(OWNER_USER).get(0).getId();
        // 结论词不认识 → 用法提示
        assertThat(replyTo("/merchant_review " + merchantId + " 也许吧", REVIEWER_USER))
                .startsWith("用法：/merchant_review");
        // 状态未变
        assertThat(merchantRepository.findById(merchantId).orElseThrow().getStatus())
                .isEqualTo(Merchant.Status.SUBMITTED.name());
    }

    /** 经真实分发链发送命令，取回回复正文。 */
    private String replyTo(String commandText, long userId) throws Exception {
        Optional<BotApiMethod<?>> reply = updateDispatcher.dispatch(update(commandText, userId));
        assertThat(reply).as("命令 %s 应被真的执行并产出回复", commandText).isPresent();
        return ((SendMessage) reply.orElseThrow()).getText();
    }

    private static Update update(String commandText, long userId) {
        int commandLength = commandText.indexOf(' ') < 0 ? commandText.length() : commandText.indexOf(' ');
        MessageEntity entity = MessageEntity.builder()
                .type(EntityType.BOTCOMMAND)
                .offset(0)
                .length(commandLength)
                .build();

        Message message = Message.builder()
                .messageId(1)
                .text(commandText)
                .entities(List.of(entity))
                .chat(Chat.builder().id(CHAT_ID).type("supergroup").build())
                .from(User.builder().id(userId).firstName("T").isBot(false).build())
                .build();

        Update update = new Update();
        update.setUpdateId(1);
        update.setMessage(message);
        return update;
    }
}

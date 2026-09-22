package com.tg.heyisheng.bot.listing;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.core.dispatch.UpdateDispatcher;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.listing.merchant.Merchant;
import com.tg.heyisheng.bot.listing.merchant.MerchantDeposit;
import com.tg.heyisheng.bot.listing.merchant.MerchantDepositRecord;
import com.tg.heyisheng.bot.listing.merchant.MerchantDepositRecordRepository;
import com.tg.heyisheng.bot.listing.merchant.MerchantDepositRepository;
import com.tg.heyisheng.bot.listing.merchant.MerchantDepositService;
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

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模块六保证金 / 退还三分支的真库测试。
 *
 * <p><b>为什么必须落真库</b>：保证金是资金账目，{@code DECIMAL(24,8)} 的精度、状态列的写入、
 * 流水的发生顺序——这些都不是 mock 能证明的。本测试的三条断言对象全是<b>库中的行</b>：
 * {@code merchant_deposits.state}、{@code merchant_deposit_records} 的 action 序列与金额。
 *
 * <p><b>同时证明 Wave 4 留下的悬空接口已接上</b>：{@code markDepositPending} / {@code markActive}
 * 在 Wave 4 只有测试调用方；本测试走的是「申请 → 复核 → 缴费 → 锁仓」的生产链路，
 * 末态 {@code merchants.status = 'ACTIVE'} 与 {@code credit_scores} 的 500 分就是它们真的被调用的证据。
 *
 * <p><b>被拒的结算必须无痕</b>：无理由的扣除若留下半截流水或改过的状态，账目就再也对不上——
 * 故专门断言「拒绝之后 state 与流水与拒绝前完全一致」。
 */
@SpringBootTest(properties = {
        "tgg.merchant.enabled=true",
        "tgg.credit.enabled=true",
        "tgg.federation.admins=" + MerchantRefundIT.REVIEWER_USER,
        "tgg.merchant.initial-score=500"
})
class MerchantRefundIT {

    static final long REVIEWER_USER = 889001L;
    private static final long OWNER_USER = 889002L;
    private static final long OUTSIDER_USER = 889003L;
    private static final long CHAT_ID = -100900003L;
    private static final BigDecimal DEPOSIT = new BigDecimal("100.00000000");

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
    private MerchantService merchants;

    @Autowired
    private MerchantDepositService depositService;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private MerchantDepositRepository depositRepository;

    @Autowired
    private MerchantDepositRecordRepository recordRepository;

    @Autowired
    private UpdateDispatcher updateDispatcher;

    @Autowired
    private GroupConfigService groupConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        clean();
        // 群开关关闭时命令一律被拒（与权限无关），故先启用本测试专用群
        groupConfigService.setEnabled(CHAT_ID, true);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM credit_scores WHERE subject_type = 'MERCHANT'");
        recordRepository.deleteAll();
        depositRepository.deleteAll();
        merchantRepository.deleteAll();
    }

    /** 走到 ACTIVE 的商家 + 已锁仓保证金（生产链路：申请 → 复核 → 缴费 → 锁仓）。 */
    private long activeMerchantWithLockedDeposit() {
        long merchantId = approvedMerchant();
        depositService.open(merchantId, DEPOSIT, "USDT");
        depositService.lock(merchantId);
        return merchantId;
    }

    /** 走到 APPROVED 的商家。 */
    private long approvedMerchant() {
        Merchant merchant = merchants.submit(OWNER_USER, "测试小铺", null, null, null);
        long id = merchant.getId();
        merchants.beginReview(id, REVIEWER_USER);
        merchants.decide(id, Merchant.Status.APPROVED, REVIEWER_USER);
        return id;
    }

    private List<String> recordActions(long merchantId) {
        return recordsOf(merchantId).stream().map(MerchantDepositRecord::getAction).toList();
    }

    private List<MerchantDepositRecord> recordsOf(long merchantId) {
        long depositId = depositRepository.findByMerchantId(merchantId).orElseThrow().getId();
        return recordRepository.findByDepositIdOrderByIdAsc(depositId);
    }

    private String stateOf(long merchantId) {
        return depositRepository.findByMerchantId(merchantId).orElseThrow().getState();
    }

    private String merchantStatusOf(long merchantId) {
        return merchantRepository.findById(merchantId).orElseThrow().getStatus();
    }

    // ---------- 完整链路：缴费 → 锁仓 → 入驻成功 ----------

    @Test
    void depositOpenThenLockActivatesMerchantAndInitializesCredit() {
        long merchantId = approvedMerchant();

        depositService.open(merchantId, DEPOSIT, "USDT");
        assertThat(merchantStatusOf(merchantId))
                .as("开通保证金应把商家推进到 DEPOSIT_PENDING")
                .isEqualTo(Merchant.Status.DEPOSIT_PENDING.name());
        assertThat(stateOf(merchantId)).isEqualTo(MerchantDeposit.State.PENDING.name());

        depositService.lock(merchantId);

        assertThat(stateOf(merchantId)).isEqualTo(MerchantDeposit.State.LOCKED.name());
        assertThat(depositRepository.findByMerchantId(merchantId).orElseThrow().getGatewayRef())
                .as("锁仓应记下网关引用（noop 实现也产生一个非空引用）")
                .isNotBlank();
        assertThat(merchantStatusOf(merchantId))
                .as("保证金到位即入驻成功——Wave 4 的 markActive 在此获得生产调用方")
                .isEqualTo(Merchant.Status.ACTIVE.name());

        Integer score = jdbcTemplate.queryForObject(
                "SELECT score FROM credit_scores WHERE subject_type = 'MERCHANT' AND subject_id = ?",
                Integer.class, merchantId);
        assertThat(score).as("入驻成功同时初始化了商家信用分").isEqualTo(500);

        assertThat(merchantRepository.findById(merchantId).orElseThrow().getTier())
                .as("锁仓成功即完成等级评定（设计文档 §2 数据流末段）；"
                        + "交易流水本阶段无数据源（传 0），故最高只到 BRONZE")
                .isEqualTo("BRONZE");
    }

    @Test
    void openIsIdempotentAndKeepsSingleDepositRow() {
        long merchantId = approvedMerchant();
        depositService.open(merchantId, DEPOSIT, "USDT");
        depositService.open(merchantId, new BigDecimal("999.00000000"), "USDT");

        List<MerchantDeposit> all = depositRepository.findAll();
        assertThat(all).as("一个商家只有一笔保证金（V6 唯一键）").hasSize(1);
        assertThat(all.get(0).getAmount())
                .as("重复开通不覆盖既有金额（幂等返回既有那笔）")
                .isEqualByComparingTo(DEPOSIT);
    }

    // ---------- 退还三分支 ----------

    @Test
    void noDisputeRefundsFullAmount() {
        long merchantId = activeMerchantWithLockedDeposit();
        depositService.freeze(merchantId, "商家申请退出", OWNER_USER);

        depositService.settle(merchantId, MerchantDepositService.Dispute.NONE, null, null, OWNER_USER);

        assertThat(stateOf(merchantId)).isEqualTo(MerchantDeposit.State.REFUNDED.name());
        assertThat(recordActions(merchantId)).containsExactly("PAY", "FREEZE", "REFUND");
        assertThat(recordsOf(merchantId).get(2).getAmount())
                .as("无争议应全额退还")
                .isEqualByComparingTo(DEPOSIT);
    }

    @Test
    void unresolvedDisputeHoldsDepositWithoutTouchingLedger() {
        long merchantId = activeMerchantWithLockedDeposit();
        depositService.freeze(merchantId, "商家申请退出", OWNER_USER);

        depositService.settle(merchantId, MerchantDepositService.Dispute.UNRESOLVED, null, null, OWNER_USER);

        assertThat(stateOf(merchantId))
                .as("有未结争议 → 暂扣：保持 FROZEN")
                .isEqualTo(MerchantDeposit.State.FROZEN.name());
        assertThat(recordActions(merchantId))
                .as("暂扣不产生新流水（既没退也没扣）")
                .containsExactly("PAY", "FREEZE");
    }

    @Test
    void compensationDeductsThenRefundsRemainder() {
        long merchantId = activeMerchantWithLockedDeposit();
        depositService.freeze(merchantId, "商家申请退出", OWNER_USER);

        depositService.settle(merchantId, MerchantDepositService.Dispute.WITH_COMPENSATION,
                new BigDecimal("30.00000000"), "赔付受损用户", OWNER_USER);

        assertThat(stateOf(merchantId)).isEqualTo(MerchantDeposit.State.REFUNDED.name());
        assertThat(recordActions(merchantId)).containsExactly("PAY", "FREEZE", "DEDUCT", "REFUND");

        List<MerchantDepositRecord> records = recordsOf(merchantId);
        assertThat(records.get(2).getAmount()).isEqualByComparingTo("30.00000000");
        assertThat(records.get(2).getReason()).as("扣除必带理由，且理由落库").isEqualTo("赔付受损用户");
        assertThat(records.get(3).getAmount())
                .as("余额退还：100 − 30 = 70（十进制精确，无浮点尾差）")
                .isEqualByComparingTo("70.00000000");
    }

    @Test
    void fullDeductionLandsOnDeductedWithoutRefundRecord() {
        long merchantId = activeMerchantWithLockedDeposit();
        depositService.freeze(merchantId, "商家申请退出", OWNER_USER);

        depositService.settle(merchantId, MerchantDepositService.Dispute.WITH_COMPENSATION,
                DEPOSIT, "全额赔付", OWNER_USER);

        assertThat(stateOf(merchantId)).isEqualTo(MerchantDeposit.State.DEDUCTED.name());
        assertThat(recordActions(merchantId))
                .as("全额扣除不应产生 0 元的 REFUND 流水")
                .containsExactly("PAY", "FREEZE", "DEDUCT");
    }

    // ---------- 被拒的结算必须无痕 ----------

    @Test
    void deductionWithoutReasonIsRejectedAndLeavesLedgerUntouched() {
        long merchantId = activeMerchantWithLockedDeposit();
        depositService.freeze(merchantId, "商家申请退出", OWNER_USER);

        assertThatThrownBy(() -> depositService.settle(merchantId,
                MerchantDepositService.Dispute.WITH_COMPENSATION, new BigDecimal("30.00000000"), "   ", OWNER_USER))
                .isInstanceOf(TggException.class);

        assertThat(stateOf(merchantId))
                .as("被拒的结算不得改动状态")
                .isEqualTo(MerchantDeposit.State.FROZEN.name());
        assertThat(recordActions(merchantId))
                .as("被拒的结算不得留下任何流水")
                .containsExactly("PAY", "FREEZE");
    }

    @Test
    void settleBeforeFreezeIsRejected() {
        long merchantId = activeMerchantWithLockedDeposit();

        assertThatThrownBy(() -> depositService.settle(merchantId,
                MerchantDepositService.Dispute.NONE, null, null, OWNER_USER))
                .as("未冻结就结算 = 争议未核查先退钱")
                .isInstanceOf(TggException.class);
        assertThat(stateOf(merchantId)).isEqualTo(MerchantDeposit.State.LOCKED.name());
    }

    // ---------- /merchant_exit 命令链路 ----------

    @Test
    void exitCommandFreezesDepositForOwnerOnly() throws Exception {
        long merchantId = activeMerchantWithLockedDeposit();

        assertThat(replyToConfirmed("/merchant_exit " + merchantId, OUTSIDER_USER))
                .as("他人不得代为退出")
                .isEqualTo("只有商家本人可以申请退出。");
        assertThat(stateOf(merchantId))
                .as("越权请求不得改动保证金状态")
                .isEqualTo(MerchantDeposit.State.LOCKED.name());

        assertThat(replyToConfirmed("/merchant_exit " + merchantId, OWNER_USER))
                .contains("保证金已冻结");
        assertThat(stateOf(merchantId)).isEqualTo(MerchantDeposit.State.FROZEN.name());
        assertThat(recordActions(merchantId))
                .as("冻结应留一条 FREEZE 流水")
                .containsExactly("PAY", "FREEZE");
    }

    @Test
    void exitCommandWithoutDepositGivesClearHint() throws Exception {
        long merchantId = approvedMerchant();

        assertThat(replyToConfirmed("/merchant_exit " + merchantId, OWNER_USER))
                .as("没缴保证金时不应静默失败")
                .contains("尚未缴纳保证金");
    }

    // ---------- /merchant_deposit 命令链路 ----------

    @Test
    void depositCommandActivatesMerchantAndInitializesCreditAndTier() throws Exception {
        long merchantId = approvedMerchant();

        assertThat(replyTo("/merchant_deposit " + merchantId + " 100.00000000", REVIEWER_USER))
                .as("平台侧确认缴纳后应完成入驻")
                .contains("保证金已确认");

        assertThat(merchantStatusOf(merchantId)).isEqualTo(Merchant.Status.ACTIVE.name());
        assertThat(stateOf(merchantId)).isEqualTo(MerchantDeposit.State.LOCKED.name());
        assertThat(merchantRepository.findById(merchantId).orElseThrow().getTier())
                .as("入驻即定级（无流水数据 → BRONZE）")
                .isEqualTo("BRONZE");

        Integer score = jdbcTemplate.queryForObject(
                "SELECT score FROM credit_scores WHERE subject_type = 'MERCHANT' AND subject_id = ?",
                Integer.class, merchantId);
        assertThat(score).as("入驻成功同时初始化商家信用分").isEqualTo(500);
    }

    @Test
    void depositCommandIsRestrictedToReviewersAndIsIdempotent() throws Exception {
        long merchantId = approvedMerchant();

        assertThat(updateDispatcher.dispatch(update("/merchant_deposit " + merchantId + " 100", OUTSIDER_USER)))
                .as("非复核人应被静默拒绝")
                .isEmpty();
        assertThat(merchantStatusOf(merchantId))
                .as("越权请求不得改动商家状态")
                .isEqualTo(Merchant.Status.APPROVED.name());

        replyTo("/merchant_deposit " + merchantId + " 100.00000000", REVIEWER_USER);
        assertThat(replyTo("/merchant_deposit " + merchantId + " 100.00000000", REVIEWER_USER))
                .as("已缴过的商家再缴一次应给明确提示，而不是重复开通")
                .contains("不用重复操作");
    }

    /**
     * 走完确认卡的往返，取回**最终**回复正文：命令 → （若回确认卡）点「确认」→ 最终回复。
     *
     * <p>给需要确认的命令用。/merchant_exit 被标注为 {@code confirm = ALWAYS}：
     * 首次派发只会拿到确认卡，真正的执行发生在「本人点确认」之后——
     * 本 helper 复刻的正是这条真实路径，而不是绕过它。
     */
    private String replyToConfirmed(String commandText, long userId) throws Exception {
        Optional<BotApiMethod<?>> first = updateDispatcher.dispatch(update(commandText, userId));
        assertThat(first).as("命令 %s 应产出回复", commandText).isPresent();
        Object reply = first.orElseThrow();

        if (!(reply instanceof SendMessage card)
                || !(card.getReplyMarkup() instanceof org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup markup)) {
            return ((SendMessage) reply).getText(); // 无需确认的命令：原样返回
        }

        // 令牌只能从卡片按钮上取——这正是真实用户点击时走的那条信息
        String prefix = com.tg.heyisheng.bot.core.dispatch.ConfirmationRequests.CONFIRM_ACTION + ":";
        String nonce = markup.getKeyboard().stream()
                .flatMap(java.util.List::stream)
                .map(b -> b.getCallbackData())
                .filter(data -> data != null && data.startsWith(prefix))
                .map(data -> data.substring(prefix.length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("确认卡里没有确认按钮：" + commandText));

        org.telegram.telegrambots.meta.api.objects.CallbackQuery query =
                new org.telegram.telegrambots.meta.api.objects.CallbackQuery();
        query.setId("cb-confirm");
        query.setFrom(User.builder().id(userId).firstName("T").isBot(false).build());
        query.setData(prefix + nonce);

        Update callback = new Update();
        callback.setUpdateId(2);
        callback.setCallbackQuery(query);

        Optional<BotApiMethod<?>> after = updateDispatcher.dispatch(callback);
        assertThat(after).as("确认后应产出最终回复").isPresent();
        return ((SendMessage) after.orElseThrow()).getText();
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

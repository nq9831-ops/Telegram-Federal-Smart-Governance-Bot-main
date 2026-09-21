package com.tg.heyisheng.bot.listing.command;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.listing.merchant.MerchantDeposit;
import com.tg.heyisheng.bot.listing.merchant.MerchantDepositService;
import com.tg.heyisheng.bot.listing.merchant.MerchantReviewGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /merchant_settle} 的单元测试。
 *
 * <p>本命令补的是全项目唯一一处<b>死胡同</b>：{@code /merchant_exit} 把保证金推到 {@code FROZEN} 后，
 * {@link MerchantDepositService#settle} 此前**只有测试调用方**（{@code src/main} 零调用），
 * 于是钱冻结后再无出口。这里把三个分支都钉住。
 *
 * <p>断言纪律：权限用例不能只断言「返回 null」——
 * 「忘了写 guard」与「guard 生效」在返回值上完全一致，必须同时断言**根本没碰服务**。
 * 参数非法的用例同理：要证明它在命令层就被挡住，而不是把脏参数递进 service 再靠异常兜。
 */
class MerchantSettleCommandHandlerTest {

    private static final long REVIEWER = 900001L;
    private static final long OUTSIDER = 12345L;
    private static final long CHAT = -100L;
    private static final long MERCHANT_ID = 42L;

    private MerchantDepositService depositService;
    private MerchantReviewGuard guard;
    private MerchantSettleCommandHandler handler;

    @BeforeEach
    void setUp() {
        depositService = mock(MerchantDepositService.class);
        guard = mock(MerchantReviewGuard.class);
        handler = new MerchantSettleCommandHandler(depositService, guard);
    }

    private static UpdateContext ctx(long userId, String args) {
        return new UpdateContext(1, userId, CHAT, 5, "merchant_settle", args);
    }

    /**
     * 一笔处于 FROZEN（可结算）的保证金。
     *
     * <p>必须**逐级**迁移（PENDING → LOCKED → FROZEN）：实体自带状态机守卫，
     * 从 PENDING 直接冻结会被它拒绝——那是正确的产品行为，测试不该绕过。
     */
    private static MerchantDeposit frozenDeposit() {
        MerchantDeposit deposit = new MerchantDeposit(MERCHANT_ID, new BigDecimal("500"), "USDT",
                Instant.parse("2026-09-01T00:00:00Z"));
        deposit.markLocked("noop-ref", Instant.parse("2026-09-10T00:00:00Z"));
        deposit.markFrozen(Instant.parse("2026-09-18T00:00:00Z"));
        return deposit;
    }

    private static String textOf(Object result) {
        assertThat(result).as("命令应回复一条消息").isInstanceOf(SendMessage.class);
        return ((SendMessage) result).getText();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 权限

    @Test
    void nonReviewerIsSilentlyIgnoredAndNeverReachesService() {
        when(guard.isReviewer(OUTSIDER)).thenReturn(false);

        assertThat(handler.handle(ctx(OUTSIDER, "42 NONE"))).isNull();

        verify(depositService, never()).find(anyLong());
        verify(depositService, never()).settle(anyLong(), any(), any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 参数解析：非法一律在命令层挡住，不进 service

    @Test
    void malformedArgumentsReplyUsageWithoutTouchingService() {
        when(guard.isReviewer(REVIEWER)).thenReturn(true);

        String[] malformed = {
                null,                              // 无操作数
                "42",                              // 缺分支
                "abc NONE",                        // 商家编号非数字
                "42 BOGUS",                        // 非法的分支名
                "42 WITH_COMPENSATION",            // 缺扣款额与理由
                "42 WITH_COMPENSATION 100",        // 缺理由（V5.0：扣除必带理由）
                "42 WITH_COMPENSATION abc 赔付",   // 扣款额非数字
                "42 WITH_COMPENSATION 0 赔付",     // 扣款额非正数
                "42 WITH_COMPENSATION -5 赔付",    // 扣款额为负
        };

        for (String args : malformed) {
            assertThat(textOf(handler.handle(ctx(REVIEWER, args))))
                    .as("操作数 %s 应回用法说明", args)
                    .isEqualTo(MerchantSettleCommandHandler.USAGE);
        }

        verify(depositService, never()).settle(anyLong(), any(), any(), any(), any());
    }

    @Test
    void unknownMerchantRepliesNotFound() {
        when(guard.isReviewer(REVIEWER)).thenReturn(true);
        when(depositService.find(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThat(textOf(handler.handle(ctx(REVIEWER, "42 NONE"))))
                .contains("没找到商家编号").contains("42");
        verify(depositService, never()).settle(anyLong(), any(), any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 三个分支

    @Test
    void noneBranchRefundsInFull() {
        when(guard.isReviewer(REVIEWER)).thenReturn(true);
        when(depositService.find(MERCHANT_ID)).thenReturn(Optional.of(frozenDeposit()));
        MerchantDeposit refunded = frozenDeposit();
        refunded.markRefunded(null, Instant.now());
        when(depositService.settle(eq(MERCHANT_ID), eq(MerchantDepositService.Dispute.NONE),
                any(), any(), any())).thenReturn(Optional.of(refunded));

        String text = textOf(handler.handle(ctx(REVIEWER, "42 NONE")));

        assertThat(text).contains("全额退还").contains("500");
        // 无争议分支不得携带扣款额与理由（它们只对 WITH_COMPENSATION 有意义）
        verify(depositService).settle(MERCHANT_ID, MerchantDepositService.Dispute.NONE, null, null, REVIEWER);
    }

    @Test
    void unresolvedBranchKeepsMoneyFrozen() {
        when(guard.isReviewer(REVIEWER)).thenReturn(true);
        when(depositService.find(MERCHANT_ID)).thenReturn(Optional.of(frozenDeposit()));
        // UNRESOLVED 的语义就是「不动状态、不写流水」——service 原样返回
        when(depositService.settle(eq(MERCHANT_ID), eq(MerchantDepositService.Dispute.UNRESOLVED),
                any(), any(), any())).thenReturn(Optional.of(frozenDeposit()));

        String text = textOf(handler.handle(ctx(REVIEWER, "42 UNRESOLVED")));

        assertThat(text).contains("冻结").contains("争议");
        verify(depositService).settle(MERCHANT_ID, MerchantDepositService.Dispute.UNRESOLVED, null, null, REVIEWER);
    }

    @Test
    void withCompensationBranchPassesDeductionAndReasonThroughVerbatim() {
        when(guard.isReviewer(REVIEWER)).thenReturn(true);
        when(depositService.find(MERCHANT_ID)).thenReturn(Optional.of(frozenDeposit()));
        MerchantDeposit after = frozenDeposit();
        after.markRefunded("多次未履约", Instant.now());
        when(depositService.settle(eq(MERCHANT_ID), eq(MerchantDepositService.Dispute.WITH_COMPENSATION),
                any(), any(), any())).thenReturn(Optional.of(after));

        // 理由是自由文本，可含空格——取尾部剩余文本，不得被空白截断
        String text = textOf(handler.handle(ctx(REVIEWER, "42 WITH_COMPENSATION 120 多次未履约 且拒绝沟通")));

        assertThat(text).contains("120").contains("多次未履约 且拒绝沟通");
        verify(depositService).settle(MERCHANT_ID, MerchantDepositService.Dispute.WITH_COMPENSATION,
                new BigDecimal("120"), "多次未履约 且拒绝沟通", REVIEWER);
    }

    @Test
    void settleRejectionIsReportedBackInsteadOfSilentlySwallowed() {
        when(guard.isReviewer(REVIEWER)).thenReturn(true);
        when(depositService.find(MERCHANT_ID)).thenReturn(Optional.of(frozenDeposit()));
        when(depositService.settle(anyLong(), any(), any(), any(), any()))
                .thenThrow(new TggException("保证金结算只适用于 FROZEN 状态，当前为 LOCKED（商家 #42）"));

        String text = textOf(handler.handle(ctx(REVIEWER, "42 NONE")));

        // 业务守卫（如「未冻结就结算」）必须让运营者看见原因，不能被吞成静默
        assertThat(text).contains("失败").contains("FROZEN");
    }

    @Test
    void branchNameIsCaseInsensitive() {
        when(guard.isReviewer(REVIEWER)).thenReturn(true);
        when(depositService.find(MERCHANT_ID)).thenReturn(Optional.of(frozenDeposit()));
        when(depositService.settle(eq(MERCHANT_ID), eq(MerchantDepositService.Dispute.NONE),
                any(), any(), any())).thenReturn(Optional.of(frozenDeposit()));

        assertThat(textOf(handler.handle(ctx(REVIEWER, "42 none")))).contains("全额退还");
    }
}

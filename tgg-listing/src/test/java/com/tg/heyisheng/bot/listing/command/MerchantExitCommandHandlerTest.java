package com.tg.heyisheng.bot.listing.command;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.listing.merchant.Merchant;
import com.tg.heyisheng.bot.listing.merchant.MerchantDeposit;
import com.tg.heyisheng.bot.listing.merchant.MerchantDepositService;
import com.tg.heyisheng.bot.listing.merchant.MerchantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
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
 * {@code /merchant_exit} 的单元测试——<b>把「退出=只冻结保证金、不改商家状态」钉成契约</b>。
 *
 * <p><b>本条测试的存在理由</b>：{@code MerchantExitCommandHandler} 处理退出时**只**把保证金推到
 * {@code FROZEN}，并不触碰 {@code merchants.status}——退出后商家仍显示 {@code ACTIVE}。这是一个
 * <b>有意的半截状态机</b>（产品已定保守路线，不引入行为变更），但「有意」只写在人的脑子里就会漂移：
 * 下一个人看到「退出了还是 ACTIVE」很容易顺手补一个状态改动，把一次无意的行为变更伪装成 bugfix。
 * 因此这里用测试把现状钉死——{@link #exitFreezesDepositOnlyAndIntentionallyLeavesStatusActive()} 一旦变红，
 * 说明有人改了退出语义，必须回到产品决定重新走流程，而不是让测试悄悄跟着改。
 *
 * <p><b>为什么不断言「handler 调了某个方法」</b>：退出语义的关键恰恰是**没发生的那件事**
 * （没改 status）。断言必须落在<b>实体的最终状态</b>上——用真实的 {@link Merchant}
 * 承载状态，命令跑完后读它的 {@code status}，才看得见「没被改动」。
 */
class MerchantExitCommandHandlerTest {

    private static final long OWNER = 42L;
    private static final long OUTSIDER = 12345L;
    private static final long CHAT = -100L;
    private static final long MERCHANT_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    private MerchantService merchants;
    private MerchantDepositService depositService;
    private MerchantExitCommandHandler handler;

    @BeforeEach
    void setUp() {
        merchants = mock(MerchantService.class);
        depositService = mock(MerchantDepositService.class);
        handler = new MerchantExitCommandHandler(merchants, depositService);
    }

    private static UpdateContext ctx(long userId, String args) {
        return new UpdateContext(1, userId, CHAT, 5, "merchant_exit", args);
    }

    /** 一名已入驻（{@code ACTIVE}）且带 id 的商家——真实链路上它必然从库里取出。 */
    private static Merchant activeMerchant() {
        Merchant merchant = withId(new Merchant(OWNER, "测试小铺", null, null, null, NOW), MERCHANT_ID);
        merchant.beginReview(NOW);
        merchant.decide(Merchant.Status.APPROVED, NOW);
        merchant.markDepositPending(NOW);
        merchant.markActive(NOW);
        return merchant;
    }

    /** 一笔冻结后的保证金（{@code freeze} 的返回值）：逐级迁移 PENDING → LOCKED → FROZEN。 */
    private static MerchantDeposit frozenDeposit() {
        MerchantDeposit deposit = new MerchantDeposit(MERCHANT_ID, new BigDecimal("500"), "USDT", NOW);
        deposit.markLocked("noop-ref", NOW);
        deposit.markFrozen(NOW);
        return deposit;
    }

    private static Merchant withId(Merchant merchant, long id) {
        try {
            Field field = Merchant.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(merchant, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法为测试实体设置 id", ex);
        }
        return merchant;
    }

    private static String textOf(Object result) {
        assertThat(result).as("命令应回复一条消息").isInstanceOf(SendMessage.class);
        return ((SendMessage) result).getText();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 契约钉住：退出=只冻结保证金，不动商家状态

    @Test
    void exitFreezesDepositOnlyAndIntentionallyLeavesStatusActive() {
        Merchant merchant = activeMerchant();
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(merchant));
        when(depositService.freeze(eq(MERCHANT_ID), any(), eq(OWNER)))
                .thenReturn(Optional.of(frozenDeposit()));

        String text = textOf(handler.handle(ctx(OWNER, "42")));

        // ① 交互结果：退出受理回执
        assertThat(text).isEqualTo(ListingMessages.exitAccepted(MERCHANT_ID));
        // ② 冻结确实被触发（这是退出的唯一副作用）
        verify(depositService).freeze(eq(MERCHANT_ID), any(), eq(OWNER));
        // ③ 核心契约：退出**不改**商家状态——退出后仍是 ACTIVE 是有意的，
        //    不是漏改。此断言变红 ⇒ 退出语义被改 ⇒ 回到产品决定重新评估。
        assertThat(merchant.getStatus())
                .as("退出=只冻结保证金、不改 status 是有意契约（保守路线）；此处变红说明退出语义被改")
                .isEqualTo(Merchant.Status.ACTIVE.name());
        // ④ 未引入任何额外的商家状态改动：handler 只读商家（find），不改商家
        verify(merchants, never()).markActive(anyLong());
        verify(merchants, never()).markDepositPending(anyLong());
        verify(merchants, never()).decide(anyLong(), any(), any());
        verify(merchants, never()).beginReview(anyLong(), any());
    }

    /**
     * 钉住「{@link Merchant.Status} 里没有退出态」这一事实。
     *
     * <p>这不是为了限制未来，而是把「当前没有退出态」写成<b>可被打破的显式契约</b>：将来真要引入
     * 退出态（{@code EXITED} 之类），这里会先红——开发者被迫走一遍「退出语义是否需要重定义」的决策，
     * 而不是悄悄加个枚举值就当无事发生。
     */
    @Test
    void merchantStatusEnumHasNoExitStateByDesign() {
        assertThat(Arrays.stream(Merchant.Status.values()).map(Enum::name))
                .as("Status 当前刻意不含退出态；新增退出态必须先重审「退出=冻结」契约")
                .containsExactly("SUBMITTED", "UNDER_REVIEW", "APPROVED", "NEED_MORE",
                        "REJECTED", "DEPOSIT_PENDING", "ACTIVE");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 守卫：身份 / 参数 / 无保证金

    @Test
    void nonOwnerIsRejectedAndNeverReachesFreeze() {
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(activeMerchant()));

        assertThat(textOf(handler.handle(ctx(OUTSIDER, "42")))).isEqualTo(ListingMessages.MERCHANT_NOT_OWNER);

        verify(depositService, never()).freeze(anyLong(), any(), any());
    }

    @Test
    void malformedArgumentsReplyUsageWithoutTouchingServices() {
        assertUsage("");        // 无操作数
        assertUsage(null);      // 无操作数
        assertUsage("abc");     // 非数字
        assertUsage("  ");      // 空白

        verify(merchants, never()).find(anyLong());
        verify(depositService, never()).freeze(anyLong(), any(), any());
    }

    private void assertUsage(String args) {
        assertThat(textOf(handler.handle(ctx(OWNER, args))))
                .as("操作数 %s 应回用法说明", args)
                .isEqualTo(MerchantExitCommandHandler.USAGE);
    }

    @Test
    void unknownMerchantRepliesNotFound() {
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThat(textOf(handler.handle(ctx(OWNER, "42")))).contains("没找到商家编号").contains("42");

        verify(depositService, never()).freeze(anyLong(), any(), any());
    }

    @Test
    void exitWithoutDepositReportsNothingToFreeze() {
        when(merchants.find(MERCHANT_ID)).thenReturn(Optional.of(activeMerchant()));
        when(depositService.freeze(eq(MERCHANT_ID), any(), eq(OWNER))).thenReturn(Optional.empty());

        assertThat(textOf(handler.handle(ctx(OWNER, "42")))).isEqualTo(ListingMessages.EXIT_NO_DEPOSIT);
    }
}

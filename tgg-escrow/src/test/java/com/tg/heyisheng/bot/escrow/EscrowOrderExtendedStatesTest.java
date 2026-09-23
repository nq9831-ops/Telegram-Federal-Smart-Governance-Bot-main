package com.tg.heyisheng.bot.escrow;

import com.tg.heyisheng.bot.common.exception.TggException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 担保订单<b>扩展状态机</b>的守卫（Wave 1：5 态 → 8 态）。
 *
 * <p>规格 §13.2 的三步创建流程（买方创建 → 卖方确认 → 资金锁定）与"交付后验收"需要
 * {@code CONFIRMED} / {@code DELIVERED} 两个中间态；协商取消与超时关闭需要 {@code CANCELLED}。
 *
 * <p><b>本文件最要紧的一条</b>：{@link #cancelledIsRejectedOnceFundsAreHeld()}——
 * 资金一经托管就不允许"取消"（要退出只能走退款）。若放行，订单能在钱仍在托管的情况下
 * 被就地关掉，资金流向与订单状态就此分叉，且不会有任何报错。
 */
class EscrowOrderExtendedStatesTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    private static EscrowOrder order() {
        return new EscrowOrder(1L, 2L, new BigDecimal("100.00000000"), "USDT", NOW);
    }

    private static EscrowOrder inState(EscrowOrder.State target) {
        EscrowOrder o = order();
        switch (target) {
            case OPEN -> { /* 初始态 */ }
            case CONFIRMED -> o.markConfirmed(NOW);
            case LOCKED -> o.markLocked(NOW);
            case DELIVERED -> {
                o.markLocked(NOW);
                o.markDelivered(NOW);
            }
            case DISPUTED -> {
                o.markLocked(NOW);
                o.markDisputed("争议", NOW);
            }
            default -> throw new IllegalStateException("本 helper 不构造终态：" + target);
        }
        return o;
    }

    @Test
    void confirmedMovesOpenToConfirmed() {
        EscrowOrder o = order();
        o.markConfirmed(NOW);
        assertThat(o.getState()).isEqualTo("CONFIRMED");
    }

    @Test
    void confirmedRejectsWhenNotOpen() {
        EscrowOrder o = inState(EscrowOrder.State.LOCKED);
        assertThatThrownBy(() -> o.markConfirmed(NOW)).isInstanceOf(TggException.class);
    }

    @Test
    void lockedFromConfirmedIsAllowed() {
        EscrowOrder o = inState(EscrowOrder.State.CONFIRMED);
        o.markLocked(NOW);
        assertThat(o.getState()).isEqualTo("LOCKED");
    }

    @Test
    void deliveredOnlyFromLocked() {
        EscrowOrder o = inState(EscrowOrder.State.LOCKED);
        o.markDelivered(NOW);
        assertThat(o.getState()).isEqualTo("DELIVERED");

        assertThatThrownBy(() -> order().markDelivered(NOW))
                .as("未托管就交付——跳过资金步骤，必须拒绝")
                .isInstanceOf(TggException.class);
    }

    @Test
    void disputedIsAllowedAfterDelivery() {
        EscrowOrder o = inState(EscrowOrder.State.DELIVERED);
        o.markDisputed("货不对板", NOW);
        assertThat(o.getState()).isEqualTo("DISPUTED");
        assertThat(o.getReason()).isEqualTo("货不对板");
    }

    @Test
    void releasedAndRefundedAreAllowedFromDelivered() {
        EscrowOrder a = inState(EscrowOrder.State.DELIVERED);
        a.markReleased(NOW);
        assertThat(a.getState()).isEqualTo("RELEASED");

        EscrowOrder b = inState(EscrowOrder.State.DELIVERED);
        b.markRefunded("验收不通过", NOW);
        assertThat(b.getState()).isEqualTo("REFUNDED");
    }

    @Test
    void cancelledIsAllowedBeforeFundsAreHeld() {
        EscrowOrder a = order();
        a.markCancelled("双方协商", NOW);
        assertThat(a.getState()).isEqualTo("CANCELLED");

        EscrowOrder b = inState(EscrowOrder.State.CONFIRMED);
        b.markCancelled("超时未支付", NOW);
        assertThat(b.getState()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelledIsRejectedOnceFundsAreHeld() {
        for (EscrowOrder.State held : new EscrowOrder.State[]{
                EscrowOrder.State.LOCKED, EscrowOrder.State.DELIVERED, EscrowOrder.State.DISPUTED}) {
            EscrowOrder o = inState(held);
            assertThatThrownBy(() -> o.markCancelled("想取消", NOW))
                    .as("资金已托管（%s）后不得取消——必须走退款路径，否则状态与资金流向分叉", held)
                    .isInstanceOf(TggException.class);
        }
    }

    @Test
    void terminalStatesRejectFurtherTransitions() {
        EscrowOrder released = inState(EscrowOrder.State.LOCKED);
        released.markReleased(NOW);
        assertThatThrownBy(() -> released.markRefunded("反悔", NOW)).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> released.markDisputed("反悔", NOW)).isInstanceOf(TggException.class);
    }
}

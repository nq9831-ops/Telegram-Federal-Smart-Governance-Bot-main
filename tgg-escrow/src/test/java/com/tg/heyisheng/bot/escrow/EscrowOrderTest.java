package com.tg.heyisheng.bot.escrow;

import com.tg.heyisheng.bot.common.exception.TggException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 担保订单<b>状态机</b>单测（不触库）。
 *
 * <p>本类守一件事：<b>非法迁移一律 fail-closed</b>。担保订单的状态决定钱能不能动——
 * 静默接受越级迁移（OPEN 直接 RELEASED）等于凭空放款，读到一个不在枚举内的状态值继续推进
 * 等于按未知语义动钱。两条都必须抛 {@link TggException}。
 */
class EscrowOrderTest {

    private static final Instant NOW = Instant.parse("2026-09-23T03:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("100.00000000");

    private static EscrowOrder open() {
        return new EscrowOrder(11L, 22L, AMOUNT, "USDT", NOW);
    }

    private static EscrowOrder locked() {
        EscrowOrder order = open();
        order.markLocked(NOW);
        return order;
    }

    private static EscrowOrder disputed() {
        EscrowOrder order = locked();
        order.markDisputed("未收到货", NOW);
        return order;
    }

    /** 直接把 {@code state} 列写成任意值——模拟数据被篡改 / 版本漂移。 */
    private static void forceState(EscrowOrder order, String state) {
        try {
            Field field = EscrowOrder.class.getDeclaredField("state");
            field.setAccessible(true);
            field.set(order, state);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法设置测试实体的 state", ex);
        }
    }

    // ---------- 合法迁移 ----------

    @Test
    void newOrderStartsOpen() {
        EscrowOrder order = open();
        assertThat(order.getState()).isEqualTo(EscrowOrder.State.OPEN.name());
        assertThat(order.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void lockMovesOpenToLocked() {
        EscrowOrder order = locked();
        assertThat(order.getState()).isEqualTo(EscrowOrder.State.LOCKED.name());
    }

    @Test
    void disputeMovesLockedToDisputedAndKeepsReason() {
        EscrowOrder order = disputed();
        assertThat(order.getState()).isEqualTo(EscrowOrder.State.DISPUTED.name());
        assertThat(order.getReason()).isEqualTo("未收到货");
    }

    @Test
    void releaseFromLockedGoesToReleased() {
        EscrowOrder order = locked();
        order.markReleased(NOW);
        assertThat(order.getState()).isEqualTo(EscrowOrder.State.RELEASED.name());
    }

    @Test
    void releaseFromDisputedResolvesInSellerFavour() {
        EscrowOrder order = disputed();
        order.markReleased(NOW);
        assertThat(order.getState())
                .as("争议裁决归卖家：DISPUTED → RELEASED")
                .isEqualTo(EscrowOrder.State.RELEASED.name());
    }

    @Test
    void refundFromLockedGoesToRefunded() {
        EscrowOrder order = locked();
        order.markRefunded("卖家同意退款", NOW);
        assertThat(order.getState()).isEqualTo(EscrowOrder.State.REFUNDED.name());
        assertThat(order.getReason()).isEqualTo("卖家同意退款");
    }

    @Test
    void refundFromDisputedResolvesInBuyerFavour() {
        EscrowOrder order = disputed();
        order.markRefunded("裁决退款", NOW);
        assertThat(order.getState())
                .as("争议裁决归买家：DISPUTED → REFUNDED")
                .isEqualTo(EscrowOrder.State.REFUNDED.name());
    }

    // ---------- 非法迁移 ----------

    @Test
    void lockRejectsAlreadyLocked() {
        EscrowOrder order = locked();
        assertThatThrownBy(() -> order.markLocked(NOW))
                .isInstanceOf(TggException.class);
    }

    @Test
    void releaseRejectsOpen() {
        EscrowOrder order = open();
        assertThatThrownBy(() -> order.markReleased(NOW))
                .as("未锁仓不得放款——越级迁移等于凭空放款")
                .isInstanceOf(TggException.class);
        assertThat(order.getState()).isEqualTo(EscrowOrder.State.OPEN.name());
    }

    @Test
    void refundRejectsOpen() {
        EscrowOrder order = open();
        assertThatThrownBy(() -> order.markRefunded("误退", NOW))
                .isInstanceOf(TggException.class);
    }

    @Test
    void disputeRejectsOpen() {
        EscrowOrder order = open();
        assertThatThrownBy(() -> order.markDisputed("争议", NOW))
                .as("未锁仓谈不上争议（无托管资金）")
                .isInstanceOf(TggException.class);
    }

    @Test
    void releasedIsTerminal() {
        EscrowOrder order = locked();
        order.markReleased(NOW);
        assertThatThrownBy(() -> order.markReleased(NOW)).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> order.markRefunded("再退", NOW)).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> order.markDisputed("再争", NOW)).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> order.markLocked(NOW)).isInstanceOf(TggException.class);
    }

    @Test
    void refundedIsTerminal() {
        EscrowOrder order = locked();
        order.markRefunded("退款", NOW);
        assertThatThrownBy(() -> order.markReleased(NOW)).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> order.markRefunded("再退", NOW)).isInstanceOf(TggException.class);
    }

    @Test
    void unknownStateFailsClosed() {
        EscrowOrder order = locked();
        forceState(order, "BOGUS");

        assertThatThrownBy(() -> order.markReleased(NOW))
                .as("读到不在枚举内的状态必须 fail-closed（拒绝推进），而不是抛裸 IllegalArgumentException")
                .isInstanceOf(TggException.class)
                .hasMessageContaining("fail-closed");
    }

    @Test
    void nullStateFailsClosed() {
        EscrowOrder order = locked();
        forceState(order, null);

        assertThatThrownBy(() -> order.markReleased(NOW))
                .isInstanceOf(TggException.class)
                .hasMessageContaining("fail-closed");
    }
}

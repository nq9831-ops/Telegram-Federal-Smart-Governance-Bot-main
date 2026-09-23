package com.tg.heyisheng.bot.escrow;

import com.tg.heyisheng.bot.common.exception.TggException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 担保交易服务单测（不触库）。
 *
 * <p>守四件事：
 * <ol>
 *   <li><b>身份闸门</b>——卖方才能确认/交付、买方才能托管/验收放款、当事方才能争议/退款/取消；
 *       越权一律在 {@code save} 之前拒绝（{@code never()} 钉住）。<b>这是资金安全的关键面</b>：
 *       若放行，任何用户都能替别人放款；</li>
 *   <li><b>守卫先于落库</b>——非法迁移 / 缺理由 / 金额非法必须在 {@code save} 之前被拒
 *       （「不留下半推进状态」）；</li>
 *   <li><b>业务前置校验</b>——买卖双方同人、金额非正、争议 / 退款缺理由；</li>
 *   <li><b>配置驱动的期限</b>——争议期与未托管超时随 {@code tgg.escrow.*} 取值。</li>
 * </ol>
 */
class EscrowServiceTest {

    private static final long BUYER = 11L;
    private static final long SELLER = 22L;
    private static final long OUTSIDER = 99L;
    private static final long ORDER_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-09-23T03:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("100.00000000");

    private final EscrowRepository orders = mock(EscrowRepository.class);
    private final EscrowProperties properties = new EscrowProperties();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final EscrowService service = new EscrowService(orders, properties, clock);

    private static EscrowOrder open() {
        return new EscrowOrder(BUYER, SELLER, AMOUNT, "USDT", NOW);
    }

    private static EscrowOrder confirmed() {
        EscrowOrder order = open();
        order.markConfirmed(NOW);
        return order;
    }

    private static EscrowOrder locked() {
        EscrowOrder order = open();
        order.markLocked(NOW);
        return order;
    }

    private static EscrowOrder delivered() {
        EscrowOrder order = locked();
        order.markDelivered(NOW);
        return order;
    }

    private void repositoryReturns(EscrowOrder order) {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(orders.save(order)).thenReturn(order);
    }

    // ---------- open ----------

    @Test
    void openRejectsSameBuyerAndSeller() {
        assertThatThrownBy(() -> service.open(BUYER, BUYER, AMOUNT, "USDT"))
                .as("担保交易需两个主体——自担保无意义")
                .isInstanceOf(TggException.class);
        verify(orders, never()).save(any());
    }

    @Test
    void openRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> service.open(BUYER, SELLER, BigDecimal.ZERO, "USDT"))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> service.open(BUYER, SELLER, null, "USDT"))
                .isInstanceOf(TggException.class);
        verify(orders, never()).save(any());
    }

    @Test
    void openDefaultsCurrencyToUsdtAndStartsOpen() {
        ArgumentCaptor<EscrowOrder> captor = ArgumentCaptor.forClass(EscrowOrder.class);
        when(orders.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        EscrowOrder saved = service.open(BUYER, SELLER, AMOUNT, null);

        assertThat(saved.getState()).isEqualTo(EscrowOrder.State.OPEN.name());
        assertThat(captor.getValue().getCurrency())
                .as("币种缺省应为 USDT（V24 列默认值）")
                .isEqualTo("USDT");
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(NOW);
    }

    // ---------- 身份闸门（资金安全关键面） ----------

    @Test
    void confirmIsSellerOnly() {
        repositoryReturns(open());

        assertThatThrownBy(() -> service.confirm(ORDER_ID, BUYER))
                .as("买方不得替卖方确认接单")
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> service.confirm(ORDER_ID, OUTSIDER))
                .isInstanceOf(TggException.class);
        verify(orders, never()).save(any());
    }

    @Test
    void lockIsBuyerOnly() {
        repositoryReturns(open());

        assertThatThrownBy(() -> service.lock(ORDER_ID, SELLER))
                .as("卖方不得替买方托管资金")
                .isInstanceOf(TggException.class);
        verify(orders, never()).save(any());
    }

    @Test
    void deliverIsSellerOnly() {
        repositoryReturns(locked());

        assertThatThrownBy(() -> service.deliver(ORDER_ID, BUYER))
                .as("买方不得替卖方标记交付")
                .isInstanceOf(TggException.class);
        verify(orders, never()).save(any());
    }

    @Test
    void releaseIsBuyerOnly() {
        repositoryReturns(delivered());

        assertThatThrownBy(() -> service.release(ORDER_ID, SELLER))
                .as("卖方不得自己放款给自己——这是最关键的一条越权守卫")
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> service.release(ORDER_ID, OUTSIDER))
                .isInstanceOf(TggException.class);
        verify(orders, never()).save(any());
    }

    @Test
    void disputeAndRefundAndCancelArePartyOnly() {
        repositoryReturns(delivered());

        assertThatThrownBy(() -> service.dispute(ORDER_ID, OUTSIDER, "理由"))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> service.refund(ORDER_ID, OUTSIDER, "理由"))
                .isInstanceOf(TggException.class);

        repositoryReturns(open());
        assertThatThrownBy(() -> service.cancel(ORDER_ID, OUTSIDER, "理由"))
                .isInstanceOf(TggException.class);
        verify(orders, never()).save(any());
    }

    // ---------- 正常迁移 ----------

    @Test
    void confirmMovesOpenToConfirmed() {
        EscrowOrder order = open();
        repositoryReturns(order);

        assertThat(service.confirm(ORDER_ID, SELLER)).contains(order);
        assertThat(order.getState()).isEqualTo(EscrowOrder.State.CONFIRMED.name());
    }

    @Test
    void lockMovesOpenToLockedAndSaves() {
        EscrowOrder order = open();
        repositoryReturns(order);

        assertThat(service.lock(ORDER_ID, BUYER)).contains(order);
        assertThat(order.getState()).isEqualTo(EscrowOrder.State.LOCKED.name());
        verify(orders).save(order);
    }

    @Test
    void deliverMovesLockedToDelivered() {
        EscrowOrder order = locked();
        repositoryReturns(order);

        assertThat(service.deliver(ORDER_ID, SELLER)).contains(order);
        assertThat(order.getState()).isEqualTo(EscrowOrder.State.DELIVERED.name());
    }

    @Test
    void cancelMovesOpenToCancelledButNotAfterCustody() {
        EscrowOrder order = open();
        repositoryReturns(order);
        assertThat(service.cancel(ORDER_ID, BUYER, "谈崩了")).contains(order);
        assertThat(order.getState()).isEqualTo(EscrowOrder.State.CANCELLED.name());

        EscrowOrder held = locked();
        repositoryReturns(held);
        assertThatThrownBy(() -> service.cancel(ORDER_ID, BUYER, "想取消"))
                .as("资金已托管后不得取消——必须走退款")
                .isInstanceOf(TggException.class);
    }

    // ---------- 既有守卫（签名已带身份） ----------

    @Test
    void lockRejectsAlreadyLockedWithoutSaving() {
        repositoryReturns(locked());

        assertThatThrownBy(() -> service.lock(ORDER_ID, BUYER))
                .isInstanceOf(TggException.class);
        verify(orders, never()).save(any());
    }

    @Test
    void unknownOrderYieldsEmptyRatherThanException() {
        when(orders.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.find(99L)).isEmpty();
        assertThat(service.confirm(99L, SELLER)).isEmpty();
        assertThat(service.lock(99L, BUYER)).isEmpty();
        assertThat(service.deliver(99L, SELLER)).isEmpty();
        assertThat(service.dispute(99L, BUYER, "争议")).isEmpty();
        assertThat(service.release(99L, BUYER)).isEmpty();
        assertThat(service.refund(99L, BUYER, "退款")).isEmpty();
        assertThat(service.cancel(99L, BUYER, "取消")).isEmpty();
    }

    @Test
    void disputeRequiresReasonBeforeSaving() {
        repositoryReturns(locked());

        assertThatThrownBy(() -> service.dispute(ORDER_ID, BUYER, "   "))
                .as("发起争议必须带理由——空理由在落库之前被拒")
                .isInstanceOf(TggException.class)
                .hasMessageContaining("理由");
        verify(orders, never()).save(any());
    }

    @Test
    void disputeMovesLockedToDisputed() {
        EscrowOrder order = locked();
        repositoryReturns(order);

        assertThat(service.dispute(ORDER_ID, BUYER, "未收到货")).contains(order);
        assertThat(order.getState()).isEqualTo(EscrowOrder.State.DISPUTED.name());
        assertThat(order.getReason()).isEqualTo("未收到货");
    }

    @Test
    void refundRequiresReasonBeforeSaving() {
        repositoryReturns(locked());

        assertThatThrownBy(() -> service.refund(ORDER_ID, BUYER, null))
                .isInstanceOf(TggException.class);
        verify(orders, never()).save(any());
    }

    @Test
    void releaseRejectsOpenOrder() {
        repositoryReturns(open());

        assertThatThrownBy(() -> service.release(ORDER_ID, BUYER))
                .as("未托管不得放款")
                .isInstanceOf(TggException.class);
        verify(orders, never()).save(any());
    }

    // ---------- 配置驱动的期限 ----------

    @Test
    void disputeDeadlineUsesConfiguredWindow() {
        properties.setDisputeWindowDays(3);
        EscrowOrder order = open();

        assertThat(service.disputeDeadline(order)).isEqualTo(NOW.plusSeconds(3 * 24 * 3600));
    }

    @Test
    void lockExpiryHonoursTimeoutAndState() {
        properties.setOrderTimeoutHours(2);
        EscrowOrder openOrder = open();
        EscrowOrder confirmedOrder = confirmed();

        Clock after = Clock.fixed(NOW.plusSeconds(3 * 3600), ZoneOffset.UTC);
        EscrowService later = new EscrowService(orders, properties, after);

        assertThat(later.isLockExpired(openOrder))
                .as("OPEN 订单超过配置的超时小时数即视为过期")
                .isTrue();
        assertThat(later.isLockExpired(confirmedOrder))
                .as("CONFIRMED（仍未托管）同样适用未托管超时")
                .isTrue();

        assertThat(later.isLockExpired(locked()))
                .as("已托管订单不适用未托管超时")
                .isFalse();
    }
}

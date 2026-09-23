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
 * <p>守三件事：
 * <ol>
 *   <li><b>守卫先于落库</b>——非法迁移 / 缺理由 / 金额非法必须在 {@code save} <b>之前</b>被拒
 *       （验证 {@code never()} 落库，钉住「不留下半推进状态」）；</li>
 *   <li><b>业务前置校验</b>——买卖双方同人、金额非正、争议 / 退款缺理由都必须拒绝；</li>
 *   <li><b>配置驱动的期限</b>——争议期与未锁仓超时随 {@code tgg.escrow.*} 取值。</li>
 * </ol>
 */
class EscrowServiceTest {

    private static final long BUYER = 11L;
    private static final long SELLER = 22L;
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

    private static EscrowOrder locked() {
        EscrowOrder order = open();
        order.markLocked(NOW);
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

    // ---------- lock ----------

    @Test
    void lockMovesOpenToLockedAndSaves() {
        EscrowOrder order = open();
        repositoryReturns(order);

        assertThat(service.lock(ORDER_ID)).contains(order);
        assertThat(order.getState()).isEqualTo(EscrowOrder.State.LOCKED.name());
        verify(orders).save(order);
    }

    @Test
    void lockRejectsAlreadyLockedWithoutSaving() {
        repositoryReturns(locked());

        assertThatThrownBy(() -> service.lock(ORDER_ID))
                .isInstanceOf(TggException.class);
        verify(orders, never()).save(any());
    }

    @Test
    void unknownOrderYieldsEmptyRatherThanException() {
        when(orders.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.find(99L)).isEmpty();
        assertThat(service.lock(99L)).isEmpty();
        assertThat(service.dispute(99L, "争议")).isEmpty();
        assertThat(service.release(99L)).isEmpty();
        assertThat(service.refund(99L, "退款")).isEmpty();
    }

    // ---------- 争议 / 裁决 ----------

    @Test
    void disputeRequiresReasonBeforeSaving() {
        repositoryReturns(locked());

        assertThatThrownBy(() -> service.dispute(ORDER_ID, "   "))
                .as("发起争议必须带理由——空理由在落库之前被拒")
                .isInstanceOf(TggException.class)
                .hasMessageContaining("理由");
        verify(orders, never()).save(any());
    }

    @Test
    void disputeMovesLockedToDisputed() {
        EscrowOrder order = locked();
        repositoryReturns(order);

        assertThat(service.dispute(ORDER_ID, "未收到货")).contains(order);
        assertThat(order.getState()).isEqualTo(EscrowOrder.State.DISPUTED.name());
        assertThat(order.getReason()).isEqualTo("未收到货");
    }

    @Test
    void refundRequiresReasonBeforeSaving() {
        repositoryReturns(locked());

        assertThatThrownBy(() -> service.refund(ORDER_ID, null))
                .isInstanceOf(TggException.class);
        verify(orders, never()).save(any());
    }

    @Test
    void releaseRejectsOpenOrder() {
        repositoryReturns(open());

        assertThatThrownBy(() -> service.release(ORDER_ID))
                .as("未锁仓不得放款")
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

        Clock after = Clock.fixed(NOW.plusSeconds(3 * 3600), ZoneOffset.UTC);
        EscrowService later = new EscrowService(orders, properties, after);

        assertThat(later.isLockExpired(openOrder))
                .as("OPEN 订单超过配置的超时小时数即视为过期")
                .isTrue();

        EscrowOrder lockedOrder = locked();
        assertThat(later.isLockExpired(lockedOrder))
                .as("已锁仓订单不适用未锁仓超时")
                .isFalse();
    }
}

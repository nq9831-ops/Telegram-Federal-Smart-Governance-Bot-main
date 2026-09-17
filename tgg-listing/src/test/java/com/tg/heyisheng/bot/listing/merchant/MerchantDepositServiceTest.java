package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.common.exception.TggException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 保证金状态机与退还三分支单测（不触库）。
 *
 * <p>本类守三件事，每件都对应一条真实的资金风险：
 * <ol>
 *   <li><b>链上失败不得推进状态</b>——网关先于状态迁移被调用，抛异常时记录必须停在原状态
 *       （否则会出现「数据库说已锁仓、链上什么都没有」）；</li>
 *   <li><b>扣除必带理由</b>——无理由的扣除必须在<b>任何网关调用与落库之前</b>被拒；</li>
 *   <li><b>三分支落到正确的终态与流水</b>——全额 / 暂扣 / 扣赔付后按余额，且金额精确（BigDecimal）。</li>
 * </ol>
 *
 * <p>真库上的落库结果由 {@code MerchantRefundIT} 断言；本层不重复，只钉住协作契约与状态机守卫。
 */
class MerchantDepositServiceTest {

    private static final long MERCHANT_ID = 7L;
    private static final long DEPOSIT_ID = 1L;
    private static final long OPERATOR = 42L;
    private static final Instant NOW = Instant.parse("2026-09-17T03:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("100.00000000");
    private static final String GATEWAY_REF = "noop-lock-ref";

    private final MerchantDepositRepository deposits = mock(MerchantDepositRepository.class);
    private final MerchantDepositRecordRepository records = mock(MerchantDepositRecordRepository.class);
    private final MerchantService merchants = mock(MerchantService.class);
    private final DepositGateway gateway = mock(DepositGateway.class);

    private final MerchantDepositService service = new MerchantDepositService(deposits, records, merchants,
            gateway, Clock.fixed(NOW, ZoneOffset.UTC));

    private void repositoryReturns(MerchantDeposit deposit) {
        when(deposits.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(deposit));
        when(deposits.save(deposit)).thenReturn(deposit);
    }

    private static MerchantDeposit withId(MerchantDeposit deposit, long id) {
        try {
            Field field = MerchantDeposit.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(deposit, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法为测试实体设置 id", ex);
        }
        return deposit;
    }

    private static MerchantDeposit pending() {
        return withId(new MerchantDeposit(MERCHANT_ID, AMOUNT, "USDT", NOW), DEPOSIT_ID);
    }

    private static MerchantDeposit locked() {
        MerchantDeposit deposit = pending();
        deposit.markLocked(GATEWAY_REF, NOW);
        return deposit;
    }

    private static MerchantDeposit frozen() {
        MerchantDeposit deposit = locked();
        deposit.markFrozen(NOW);
        return deposit;
    }

    // ---------- open ----------

    @Test
    void openRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> service.open(MERCHANT_ID, BigDecimal.ZERO, "USDT"))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> service.open(MERCHANT_ID, null, "USDT"))
                .isInstanceOf(TggException.class);
    }

    @Test
    void openAdvancesMerchantAndWritesPayRecord() {
        when(merchants.markDepositPending(MERCHANT_ID))
                .thenReturn(Optional.of(mock(Merchant.class)));
        ArgumentCaptor<MerchantDeposit> depositCaptor = ArgumentCaptor.forClass(MerchantDeposit.class);
        when(deposits.save(depositCaptor.capture())).thenAnswer(inv -> {
            MerchantDeposit saved = inv.getArgument(0);
            return withId(saved, DEPOSIT_ID);
        });

        assertThat(service.open(MERCHANT_ID, AMOUNT, null)).isPresent();

        assertThat(depositCaptor.getValue().getCurrency())
                .as("币种缺省应为 USDT（V6 列默认值）")
                .isEqualTo("USDT");
        ArgumentCaptor<MerchantDepositRecord> recordCaptor =
                ArgumentCaptor.forClass(MerchantDepositRecord.class);
        verify(records).save(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getAction()).isEqualTo("PAY");
        verify(merchants).markDepositPending(MERCHANT_ID);
    }

    @Test
    void openIsIdempotentWhenDepositAlreadyExists() {
        MerchantDeposit existing = pending();
        when(deposits.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(existing));

        assertThat(service.open(MERCHANT_ID, AMOUNT, "USDT")).contains(existing);

        verify(merchants, never()).markDepositPending(anyLong());
        verify(deposits, never()).save(any());
    }

    // ---------- lock ----------

    @Test
    void lockAdvancesMerchantToActive() {
        MerchantDeposit deposit = pending();
        repositoryReturns(deposit);
        when(gateway.lock(MERCHANT_ID, AMOUNT, "USDT")).thenReturn(GATEWAY_REF);

        assertThat(service.lock(MERCHANT_ID)).isPresent();

        assertThat(deposit.getState()).isEqualTo(MerchantDeposit.State.LOCKED.name());
        assertThat(deposit.getGatewayRef()).isEqualTo(GATEWAY_REF);
        verify(merchants).markActive(MERCHANT_ID);
    }

    @Test
    void lockKeepsStateWhenGatewayFails() {
        MerchantDeposit deposit = pending();
        repositoryReturns(deposit);
        when(gateway.lock(anyLong(), any(), anyString()))
                .thenThrow(new IllegalStateException("chain down"));

        assertThatThrownBy(() -> service.lock(MERCHANT_ID))
                .isInstanceOf(IllegalStateException.class);

        assertThat(deposit.getState())
                .as("链上锁仓失败时状态必须停在 PENDING——绝不出现「库里已锁仓、链上没有」")
                .isEqualTo(MerchantDeposit.State.PENDING.name());
        assertThat(deposit.getGatewayRef()).isNull();
        verify(merchants, never()).markActive(anyLong());
    }

    // ---------- freeze ----------

    @Test
    void freezeMovesLockedToFrozenAndWritesRecord() {
        MerchantDeposit deposit = locked();
        repositoryReturns(deposit);

        assertThat(service.freeze(MERCHANT_ID, "商家申请退出", OPERATOR)).isPresent();

        assertThat(deposit.getState()).isEqualTo(MerchantDeposit.State.FROZEN.name());
        ArgumentCaptor<MerchantDepositRecord> captor = ArgumentCaptor.forClass(MerchantDepositRecord.class);
        verify(records).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("FREEZE");
        assertThat(captor.getValue().getOperator()).isEqualTo(OPERATOR);
    }

    @Test
    void freezeRejectsDepositStillPending() {
        repositoryReturns(pending());

        assertThatThrownBy(() -> service.freeze(MERCHANT_ID, "退出", OPERATOR))
                .as("未锁仓的保证金谈不上冻结")
                .isInstanceOf(TggException.class);
        // 业务守卫必须先于网关调用——否则「状态不对」会先触发一个不可回滚的链上冻结动作
        verify(gateway, never()).freeze(anyLong(), any());
    }

    @Test
    void lockRejectsDepositAlreadyLockedWithoutCallingGateway() {
        repositoryReturns(locked());

        assertThatThrownBy(() -> service.lock(MERCHANT_ID))
                .isInstanceOf(TggException.class);
        verify(gateway, never()).lock(anyLong(), any(), anyString());
    }

    // ---------- settle 三分支 ----------

    @Test
    void settleWithoutDisputeRefundsInFull() {
        MerchantDeposit deposit = frozen();
        repositoryReturns(deposit);

        service.settle(MERCHANT_ID, MerchantDepositService.Dispute.NONE, null, null, OPERATOR);

        assertThat(deposit.getState()).isEqualTo(MerchantDeposit.State.REFUNDED.name());
        verify(gateway).refund(MERCHANT_ID, GATEWAY_REF, AMOUNT);
        assertThat(savedActions()).as("无争议只应有一条 REFUND 流水").containsExactly("REFUND");
    }

    @Test
    void settleWithUnresolvedDisputeHoldsFunds() {
        MerchantDeposit deposit = frozen();
        repositoryReturns(deposit);

        service.settle(MERCHANT_ID, MerchantDepositService.Dispute.UNRESOLVED, null, null, OPERATOR);

        assertThat(deposit.getState())
                .as("有未结争议时暂扣：保持 FROZEN，既不退还也不没收")
                .isEqualTo(MerchantDeposit.State.FROZEN.name());
        verify(gateway, never()).refund(anyLong(), anyString(), any());
        verify(gateway, never()).deduct(anyLong(), anyString(), any(), anyString());
        verify(records, never()).save(any());
        verify(deposits, never()).save(any());
    }

    @Test
    void settleWithCompensationDeductsThenRefundsRemainder() {
        MerchantDeposit deposit = frozen();
        repositoryReturns(deposit);

        service.settle(MERCHANT_ID, MerchantDepositService.Dispute.WITH_COMPENSATION,
                new BigDecimal("30.00000000"), "赔付用户 A", OPERATOR);

        assertThat(deposit.getState()).isEqualTo(MerchantDeposit.State.REFUNDED.name());
        verify(gateway).deduct(MERCHANT_ID, GATEWAY_REF, new BigDecimal("30.00000000"), "赔付用户 A");
        verify(gateway).refund(MERCHANT_ID, GATEWAY_REF, new BigDecimal("70.00000000"));

        ArgumentCaptor<MerchantDepositRecord> captor = ArgumentCaptor.forClass(MerchantDepositRecord.class);
        verify(records, times(2)).save(captor.capture());
        List<MerchantDepositRecord> saved = captor.getAllValues();
        assertThat(saved).extracting(MerchantDepositRecord::getAction)
                .as("先扣后赔：DEDUCT 在前、REFUND 在后（审计顺序即发生顺序）")
                .containsExactly("DEDUCT", "REFUND");
        assertThat(saved.get(0).getAmount()).isEqualByComparingTo("30.00000000");
        assertThat(saved.get(0).getReason()).isEqualTo("赔付用户 A");
        assertThat(saved.get(1).getAmount()).isEqualByComparingTo("70.00000000");
    }

    @Test
    void settleWithFullDeductionLandsOnDeducted() {
        MerchantDeposit deposit = frozen();
        repositoryReturns(deposit);

        service.settle(MERCHANT_ID, MerchantDepositService.Dispute.WITH_COMPENSATION,
                AMOUNT, "全额赔付", OPERATOR);

        assertThat(deposit.getState())
                .as("扣除额等于保证金时落 DEDUCTED（终态），而不是退 0 元的 REFUNDED")
                .isEqualTo(MerchantDeposit.State.DEDUCTED.name());
        verify(gateway).deduct(MERCHANT_ID, GATEWAY_REF, AMOUNT, "全额赔付");
        verify(gateway, never()).refund(anyLong(), anyString(), any());
        assertThat(savedActions()).containsExactly("DEDUCT");
    }

    // ---------- settle 的守卫 ----------

    @Test
    void settleRejectsDeductionWithoutReason() {
        MerchantDeposit deposit = frozen();
        repositoryReturns(deposit);

        assertThatThrownBy(() -> service.settle(MERCHANT_ID,
                MerchantDepositService.Dispute.WITH_COMPENSATION, new BigDecimal("30"), "   ", OPERATOR))
                .as("V5.0 约束：扣除必带理由")
                .isInstanceOf(TggException.class)
                .hasMessageContaining("理由");

        verify(gateway, never()).deduct(anyLong(), anyString(), any(), anyString());
        verify(deposits, never()).save(any());
        assertThat(deposit.getState())
                .as("被拒的结算不得改动任何状态")
                .isEqualTo(MerchantDeposit.State.FROZEN.name());
    }

    @Test
    void settleRejectsDeductionExceedingDeposit() {
        MerchantDeposit deposit = frozen();
        repositoryReturns(deposit);

        assertThatThrownBy(() -> service.settle(MERCHANT_ID,
                MerchantDepositService.Dispute.WITH_COMPENSATION, new BigDecimal("100.00000001"), "超扣", OPERATOR))
                .as("超过保证金金额的扣除必须拒绝，而不是静默按余额截断")
                .isInstanceOf(TggException.class);
        verify(gateway, never()).deduct(anyLong(), anyString(), any(), anyString());
    }

    @Test
    void settleRejectsWhenDepositNotFrozen() {
        MerchantDeposit deposit = locked();
        repositoryReturns(deposit);

        assertThatThrownBy(() -> service.settle(MERCHANT_ID,
                MerchantDepositService.Dispute.NONE, null, null, OPERATOR))
                .as("未冻结不得结算（否则可在争议未核查时直接退钱）")
                .isInstanceOf(TggException.class);
    }

    @Test
    void unknownMerchantYieldsEmptyRatherThanException() {
        when(deposits.findByMerchantId(99L)).thenReturn(Optional.empty());

        assertThat(service.find(99L)).isEmpty();
        assertThat(service.lock(99L)).isEmpty();
        assertThat(service.freeze(99L, "退出", OPERATOR)).isEmpty();
        assertThat(service.settle(99L, MerchantDepositService.Dispute.NONE, null, null, OPERATOR)).isEmpty();
        assertThat(service.open(99L, AMOUNT, "USDT")).isEmpty();
    }

    /** 捕获 {@code records.save} 收到的全部动作类型（按调用顺序）。 */
    private List<String> savedActions() {
        ArgumentCaptor<MerchantDepositRecord> captor = ArgumentCaptor.forClass(MerchantDepositRecord.class);
        verify(records, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream().map(MerchantDepositRecord::getAction).toList();
    }
}

package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.common.exception.TggException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * 模块六 · 保证金服务：承载<b>保证金状态机</b>与<b>退还三分支</b>（设计文档 §3.1）。
 *
 * <pre>
 * open ──▶ PENDING ──lock──▶ LOCKED ──freeze──▶ FROZEN ──┬─ settle(NONE)              ─▶ REFUNDED
 *        （已创建）        （已锁仓）          （已冻结）  ├─ settle(UNRESOLVED)        ─▶ 保持 FROZEN
 *                                                        ├─ settle(WITH_COMPENSATION)  ─▶ REFUNDED（余额）
 *                                                        └─ settle(全额扣除)           ─▶ DEDUCTED
 * </pre>
 *
 * <p><b>「完整做」的边界</b>：账本 / 状态机 / 流水 / 退还三分支判定<b>全部是真实逻辑</b>，
 * 只有链上那一半落在 {@link DepositGateway} 背后（默认 {@link NoopDepositGateway}）——
 * 因此本波次的一切都可在真库上端到端验证（见 {@code MerchantRefundIT}）。
 *
 * <p><b>fail-closed 的三处落点</b>：
 * <ol>
 *   <li><b>链上失败即整体回滚</b>：{@code gateway.*} 抛异常 → 事务回滚 → 状态与流水都不推进
 *       （绝不留下「状态说已锁仓、链上没有」的裂缝）；</li>
 *   <li><b>扣除必带理由</b>：金额 &gt; 0 的扣除若 {@code reason} 为空直接拒绝，且拒绝发生在
 *       网关调用与落库<b>之前</b>；</li>
 *   <li><b>扣除额不得超过保证金</b>：超过即拒绝，而不是默默按余额截断
 *       （截断会把「多扣了」伪装成正常结算）。</li>
 * </ol>
 *
 * <p><b>为什么本类持有 {@link MerchantService}</b>：保证金是商家入驻流程的一环——
 * 「缴费开始」要把商家推到 {@code DEPOSIT_PENDING}，「锁仓成功」要把它推到 {@code ACTIVE}
 * （含信用分初始化）。这层编排放在保证金服务里，因为<b>它才是流程的驱动者</b>；
 * 反过来让商家服务依赖保证金服务会让「入驻」这个领域对象背上资金细节。
 */
public class MerchantDepositService {

    private static final Logger log = LoggerFactory.getLogger(MerchantDepositService.class);

    /** 缺省币种（V6 的 {@code currency} 列默认值）。 */
    static final String DEFAULT_CURRENCY = "USDT";

    /** 退出结算时的争议情况（退还三分支的判据）。 */
    public enum Dispute {
        /** 无争议 → 全额退还。 */
        NONE,
        /** 有未结争议 → 暂扣（保持 FROZEN，待争议结案）。 */
        UNRESOLVED,
        /** 有赔付 → 扣除后按余额退还（扣除额等于全额时落 DEDUCTED）。 */
        WITH_COMPENSATION
    }

    private final MerchantDepositRepository deposits;
    private final MerchantDepositRecordRepository records;
    private final MerchantService merchants;
    private final DepositGateway gateway;
    private final Clock clock;

    public MerchantDepositService(MerchantDepositRepository deposits,
                                  MerchantDepositRecordRepository records,
                                  MerchantService merchants,
                                  DepositGateway gateway,
                                  Clock clock) {
        this.deposits = deposits;
        this.records = records;
        this.merchants = merchants;
        this.gateway = gateway;
        this.clock = clock;
    }

    /** 取某商家的保证金（无则空）。 */
    public Optional<MerchantDeposit> find(long merchantId) {
        return deposits.findByMerchantId(merchantId);
    }

    /**
     * 开通保证金：创建一笔 {@code PENDING} 记录，并把商家从 {@code APPROVED} 推到
     * {@code DEPOSIT_PENDING}（缴纳阶段开始）。
     *
     * <p><b>幂等</b>：一个商家只有一笔保证金（V6 唯一键），已有则原样返回，不重复推进状态。
     *
     * @return 空 = 商家不存在（{@code markDepositPending} 未命中，事务内无副作用）
     * @throws TggException 商家当前状态不允许进入缴纳阶段（非 {@code APPROVED}）
     */
    @Transactional
    public Optional<MerchantDeposit> open(long merchantId, BigDecimal amount, String currency) {
        requirePositive(amount);
        Optional<MerchantDeposit> existing = deposits.findByMerchantId(merchantId);
        if (existing.isPresent()) {
            log.info("商家 #{} 已有保证金记录（幂等：不重复开通）。", merchantId);
            return existing;
        }

        Optional<Merchant> advanced = merchants.markDepositPending(merchantId);
        if (advanced.isEmpty()) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        MerchantDeposit deposit = deposits.save(new MerchantDeposit(merchantId, amount,
                (currency == null || currency.isBlank()) ? DEFAULT_CURRENCY : currency, now));
        records.save(new MerchantDepositRecord(deposit.getId(), MerchantDepositRecord.Action.PAY,
                amount, null, null, now));
        log.info("保证金已开通：商家 #{} 金额 {}（等待锁仓）。", merchantId, amount);
        return Optional.of(deposit);
    }

    /**
     * 锁仓：{@code PENDING} → {@code LOCKED}，并把商家推到 {@code ACTIVE}（入驻成功 + 信用分初始化）。
     *
     * <p><b>先调网关再改状态</b>：链上失败会抛异常并回滚整个事务，于是状态与商家都停在原地——
     * 这正是「不能让数据库说已锁仓而链上没有」的落点。
     *
     * @return 空 = 该商家没有保证金记录
     */
    @Transactional
    public Optional<MerchantDeposit> lock(long merchantId) {
        Optional<MerchantDeposit> found = deposits.findByMerchantId(merchantId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        MerchantDeposit deposit = found.get();
        // 守卫先行：可预期的业务失败（状态不对）绝不能触发链上动作——链上动作不可回滚，
        // 而事务只能回滚本地状态。若反过来先调网关，就会出现「链上已锁仓、库里没锁」的裂缝。
        if (!MerchantDeposit.State.PENDING.name().equals(deposit.getState())) {
            throw new TggException("保证金锁仓只适用于 PENDING 状态，当前为 " + deposit.getState()
                    + "（商家 #" + merchantId + "）");
        }
        String ref = gateway.lock(merchantId, deposit.getAmount(), deposit.getCurrency());
        deposit.markLocked(ref, clock.instant());
        deposits.save(deposit);

        merchants.markActive(merchantId);
        // 入驻成功即评定等级（设计文档 §2 数据流：…→ 保证金 → 等级评定 → ACTIVE）。
        // 流水量本阶段无数据源（交易属模块十二），故传 0——没有数据就不虚升等级。
        merchants.evaluateTier(merchantId, deposit.getAmount(), 0L);
        log.info("保证金已锁仓，商家入驻完成：商家 #{} ref={}。", merchantId, ref);
        return Optional.of(deposit);
    }

    /**
     * 冻结（退出申请受理）：{@code LOCKED} → {@code FROZEN}。
     *
     * <p>冻结是结算的<b>前置条件</b>——只有冻结后才会谈「无争议全额 / 有争议暂扣 / 扣赔付后退还」，
     * 因此 {@link #settle} 会拒绝非 FROZEN 的记录。
     *
     * @return 空 = 该商家没有保证金记录
     */
    @Transactional
    public Optional<MerchantDeposit> freeze(long merchantId, String reason, Long operator) {
        Optional<MerchantDeposit> found = deposits.findByMerchantId(merchantId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        MerchantDeposit deposit = found.get();
        // 守卫先行（理由同 lock）：未锁仓的保证金谈不上冻结，且不得先对链上发起冻结。
        if (!MerchantDeposit.State.LOCKED.name().equals(deposit.getState())) {
            throw new TggException("保证金冻结只适用于 LOCKED 状态，当前为 " + deposit.getState()
                    + "（商家 #" + merchantId + "）");
        }
        Instant now = clock.instant();
        gateway.freeze(merchantId, deposit.getGatewayRef());
        deposit.markFrozen(now);
        deposits.save(deposit);
        records.save(new MerchantDepositRecord(deposit.getId(), MerchantDepositRecord.Action.FREEZE,
                deposit.getAmount(), reason, operator, now));
        log.info("保证金已冻结：商家 #{}（待争议核查后结算）。", merchantId);
        return Optional.of(deposit);
    }

    /**
     * 结算（退还三分支）。
     *
     * <ul>
     *   <li>{@link Dispute#NONE}：全额退还 → {@code REFUNDED} + 流水 {@code REFUND}；</li>
     *   <li>{@link Dispute#UNRESOLVED}：暂扣 → <b>保持 FROZEN</b>，不动状态、不写流水
     *       （争议结案后再调本方法）；</li>
     *   <li>{@link Dispute#WITH_COMPENSATION}：先扣赔付（必带理由）→ 余额退还；
     *       余额为 0 时落 {@code DEDUCTED}。</li>
     * </ul>
     *
     * @param deduction 仅 {@code WITH_COMPENSATION} 使用；其余分支忽略
     * @param reason    扣除理由；{@code WITH_COMPENSATION} 时必填（V5.0「扣除必带理由」）
     * @return 空 = 该商家没有保证金记录
     * @throws TggException 非 FROZEN 状态结算 / 扣除无理由 / 扣除额超过保证金
     */
    @Transactional
    public Optional<MerchantDeposit> settle(long merchantId, Dispute dispute, BigDecimal deduction,
                                            String reason, Long operator) {
        Optional<MerchantDeposit> found = deposits.findByMerchantId(merchantId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        MerchantDeposit deposit = found.get();
        if (!MerchantDeposit.State.FROZEN.name().equals(deposit.getState())) {
            throw new TggException("保证金结算只适用于 FROZEN 状态，当前为 " + deposit.getState()
                    + "（商家 #" + merchantId + "）");
        }

        Instant now = clock.instant();
        switch (dispute) {
            case NONE -> refundInFull(deposit, operator, now);
            case UNRESOLVED -> log.info("保证金暂扣（争议未结）：商家 #{}，保持 FROZEN。", merchantId);
            case WITH_COMPENSATION -> refundAfterDeduction(deposit, deduction, reason, operator, now);
        }
        return Optional.of(deposit);
    }

    /** 无争议：全额退还。 */
    private void refundInFull(MerchantDeposit deposit, Long operator, Instant now) {
        gateway.refund(deposit.getMerchantId(), deposit.getGatewayRef(), deposit.getAmount());
        deposit.markRefunded(null, now);
        deposits.save(deposit);
        records.save(new MerchantDepositRecord(deposit.getId(), MerchantDepositRecord.Action.REFUND,
                deposit.getAmount(), null, operator, now));
    }

    /** 有赔付：扣赔付（必带理由）后退还余额；余额为 0 落 DEDUCTED。 */
    private void refundAfterDeduction(MerchantDeposit deposit, BigDecimal deduction, String reason,
                                      Long operator, Instant now) {
        requirePositive(deduction);
        requireReason(reason);
        if (deduction.compareTo(deposit.getAmount()) > 0) {
            throw new TggException("扣除额（" + deduction + "）不得超过保证金金额（"
                    + deposit.getAmount() + "）——不截断、不静默按余额处理");
        }

        gateway.deduct(deposit.getMerchantId(), deposit.getGatewayRef(), deduction, reason);
        records.save(new MerchantDepositRecord(deposit.getId(), MerchantDepositRecord.Action.DEDUCT,
                deduction, reason, operator, now));

        BigDecimal remaining = deposit.getAmount().subtract(deduction);
        if (remaining.signum() > 0) {
            gateway.refund(deposit.getMerchantId(), deposit.getGatewayRef(), remaining);
            deposit.markRefunded(reason, now);
            records.save(new MerchantDepositRecord(deposit.getId(), MerchantDepositRecord.Action.REFUND,
                    remaining, null, operator, now));
        } else {
            deposit.markDeducted(reason, now);
        }
        deposits.save(deposit);
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new TggException("保证金金额必须为正数（实为 " + amount + "）");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new TggException("扣除保证金必须提供理由（V5.0 约束：扣除必带理由）");
        }
    }
}

package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.common.exception.TggException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;

/**
 * 商家保证金账本（表 {@code merchant_deposits}，模块六 · 设计文档 §5）。
 *
 * <p>表结构由 Flyway <b>V6</b> 管理（V6 当初已建本表，本波次<b>无新迁移</b>），
 * JPA 侧 {@code ddl-auto: validate}。
 *
 * <p><b>状态机</b>（设计文档 §3.1）：
 * <pre>
 * PENDING ──markLocked──▶ LOCKED ──markFrozen──▶ FROZEN ──┬─ markRefunded ─▶ REFUNDED
 *   （已创建）           （已锁仓）           （已冻结）    └─ markDeducted ─▶ DEDUCTED
 * </pre>
 *
 * <p><b>金额是 {@link BigDecimal} 而不是 double</b>：{@code DECIMAL(24,8)} 是精确十进制，
 * 用二进制浮点表示会在比对与求差时引入不可解释的尾差（「退还 99.99999999 还是 100」这种问题
 * 在保证金场景里是资金问题，不是显示问题）。
 *
 * <p><b>{@link #merchantId} 唯一</b>（V6 的 {@code uk_deposit_merchant}）：一个商家只有一笔保证金。
 * 因此创建走「先查后建」，由唯一键兜底并发——重复创建会抛唯一约束，而不是悄悄产生第二笔。
 *
 * <p><b>非法迁移一律抛异常</b>：保证金状态决定钱能不能动，静默接受越级迁移
 * （如 PENDING 直接 REFUNDED）等于凭空退还一笔从未锁仓的钱。
 */
@Entity
@Table(name = "merchant_deposits")
public class MerchantDeposit {

    /** 保证金状态。取值即 V5.0 的流程节点，与 {@code merchant_deposits.state} 列的字符串一一对应。 */
    public enum State {
        /** 已创建（待缴纳 / 待锁仓）。 */
        PENDING,
        /** 已锁仓。 */
        LOCKED,
        /** 已冻结（退出申请受理，暂停动用）。 */
        FROZEN,
        /** 已退还（终态）。 */
        REFUNDED,
        /** 已扣除（终态，不予退还）。 */
        DEDUCTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private long merchantId;

    @Column(name = "amount", nullable = false, precision = 24, scale = 8)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "gateway_ref", length = 128)
    private String gatewayRef;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantDeposit() {
    }

    /** 新建一笔待锁仓的保证金：状态恒从 {@link State#PENDING} 起步。 */
    public MerchantDeposit(long merchantId, BigDecimal amount, String currency, Instant createdAt) {
        this.merchantId = merchantId;
        this.amount = amount;
        this.currency = currency;
        this.state = State.PENDING.name();
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /** 锁仓成功：{@code PENDING} → {@code LOCKED}，并记下网关引用。 */
    public void markLocked(String gatewayRef, Instant now) {
        requireState(State.PENDING);
        this.gatewayRef = gatewayRef;
        this.state = State.LOCKED.name();
        this.updatedAt = now;
    }

    /** 冻结（退出申请受理）：{@code LOCKED} → {@code FROZEN}。 */
    public void markFrozen(Instant now) {
        requireState(State.LOCKED);
        this.state = State.FROZEN.name();
        this.updatedAt = now;
    }

    /** 退还完毕（可能是扣除后的余额）：{@code FROZEN} → {@code REFUNDED}。 */
    public void markRefunded(String reason, Instant now) {
        requireState(State.FROZEN);
        this.reason = reason;
        this.state = State.REFUNDED.name();
        this.updatedAt = now;
    }

    /** 全额扣除（不予退还）：{@code FROZEN} → {@code DEDUCTED}。 */
    public void markDeducted(String reason, Instant now) {
        requireState(State.FROZEN);
        this.reason = reason;
        this.state = State.DEDUCTED.name();
        this.updatedAt = now;
    }

    private void requireState(State... allowed) {
        State current = State.valueOf(this.state);
        for (State candidate : allowed) {
            if (current == candidate) {
                return;
            }
        }
        throw new TggException("保证金状态迁移非法：当前 " + current + "，本操作只允许 "
                + Arrays.toString(allowed) + (id == null ? "" : "（保证金 #" + id + "）"));
    }

    public Long getId() {
        return id;
    }

    public long getMerchantId() {
        return merchantId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getState() {
        return state;
    }

    public String getGatewayRef() {
        return gatewayRef;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

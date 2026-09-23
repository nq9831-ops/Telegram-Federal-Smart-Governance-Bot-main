package com.tg.heyisheng.bot.escrow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 担保交易<b>资金流水</b>（表 {@code escrow_fund_ledger}，Flyway V25）——<b>只追加</b>。
 *
 * <p><b>为什么独立于通用 {@code audit_log}</b>：通用审计的 {@code detail} 是 VARCHAR(512) 自由文本、
 * 无金额/币种列，且写入<b>弱一致</b>（{@code AuditService} 失败只 log.error、不阻断业务）。
 * 资金流水需要「结构化金额 + 强一致 + 可对账」——两者语义与可靠性要求不同，不可混用。
 *
 * <p><b>不变式</b>：字段全私有、<b>无 setter</b>（写入即事实，不可改写）；
 * 方向由 {@link Direction} 表达，{@code amount} 恒为正数（方向含在 direction 里，不靠符号）。
 *
 * <p><b>幂等</b>：{@code idempotencyKey} 上的唯一约束是去重的唯一依据——写入走
 * {@link EscrowFundLedgerRepository#insertIfAbsent}（native {@code INSERT IGNORE}），
 * 而非 catch 唯一约束异常（flush 失败会让 Hibernate session 不可用）。
 *
 * <p><b>链上两列</b>（{@code chain_ref} / {@code chain_state}）为 Wave 5 链上接入预留：
 * 未上链时 {@code chain_state='LOCAL'}、{@code chain_ref} 为 NULL——不与账本流程耦合。
 */
@Entity
@Table(name = "escrow_fund_ledger")
public class EscrowFundLedger {

    /** 资金动作方向（与 V25 的 {@code direction} 列取值一一对应）。 */
    public enum Direction {
        /** 买方资金进入托管（锁定）。 */
        HOLD,
        /** 托管资金释放给卖方。 */
        RELEASE,
        /** 托管资金退回买方。 */
        REFUND,
        /** 从败诉方保证金扣除罚金（仲裁费兜底链第二级）。 */
        DEDUCT_PENALTY,
        /** 联邦仲裁基金垫付（兜底链末级）。 */
        FUND_ADVANCE
    }

    /** 链上状态（Wave 5 使用；当前恒 {@link #CHAIN_LOCAL}）。 */
    public static final String CHAIN_LOCAL = "LOCAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "direction", nullable = false, length = 24)
    private String direction;

    @Column(name = "amount", nullable = false, precision = 24, scale = 8)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    @Column(name = "subject_user_id")
    private Long subjectUserId;

    @Column(name = "chain_ref", length = 128)
    private String chainRef;

    @Column(name = "chain_state", nullable = false, length = 16)
    private String chainState;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "idempotency_key", nullable = false, length = 191)
    private String idempotencyKey;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** JPA 用。 */
    protected EscrowFundLedger() {
    }

    /**
     * 记一条资金流水。
     *
     * @param orderId        关联订单（罚金/基金类动作可为 null）
     * @param direction      动作方向
     * @param amount         金额（须 &gt; 0；方向由 direction 表达）
     * @param currency       币种（担保交易默认 USDT）
     * @param subjectUserId  资金动作指向的用户（赔付收款方/被扣方；可为 null）
     * @param reason         动作理由（不含私密正文；可为 null）
     * @param idempotencyKey 业务去重键（必填——无键则无法保证不重复入账）
     * @param occurredAt     发生时刻
     */
    public EscrowFundLedger(Long orderId, Direction direction, BigDecimal amount, String currency,
                            Long subjectUserId, String reason, String idempotencyKey, Instant occurredAt) {
        if (direction == null) {
            throw new IllegalArgumentException("资金方向不可为空");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("资金金额必须为正数——方向由 direction 表达，不靠符号");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("幂等键不可为空——无键则无法保证不重复入账");
        }
        this.orderId = orderId;
        this.direction = direction.name();
        this.amount = amount;
        this.currency = currency;
        this.subjectUserId = subjectUserId;
        this.chainState = CHAIN_LOCAL;
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getSubjectUserId() {
        return subjectUserId;
    }

    public String getChainRef() {
        return chainRef;
    }

    public String getChainState() {
        return chainState;
    }

    public String getReason() {
        return reason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}

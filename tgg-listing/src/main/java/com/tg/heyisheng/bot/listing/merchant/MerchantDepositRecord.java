package com.tg.heyisheng.bot.listing.merchant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 保证金流水（表 {@code merchant_deposit_records}，模块六 · 设计文档 §5）。
 *
 * <p><b>只追加、不修改、不删除</b>：这类账目流水是审计对象，任何「改一笔」的诉求都应
 * 以新的一条反向流水表达。表结构由 Flyway <b>V6</b> 管理，本波次无新迁移。
 *
 * <p><b>{@link #reason} 对 DEDUCT 是必填</b>（V5.0 约束「扣除必带理由」）——注意本实体
 * <b>不</b>在构造时校验它：校验发生在 {@code MerchantDepositService} 的入口
 * （「没有理由就不允许走到扣除」），实体只管持久化。把校验放在服务入口的好处是
 * 失败发生在任何链上动作与落库之前，不留下半截事务。
 *
 * <p><b>{@link #operator} 可空</b>：链上自动动作（或系统结算）没有人类操作者；
 * 有操作者时记其 userId（定位「谁做的这个决定」是审计的核心诉求之一）。
 */
@Entity
@Table(name = "merchant_deposit_records")
public class MerchantDepositRecord {

    /** 流水动作类型，对应 V6 的 {@code action} 列注释。 */
    public enum Action {
        /** 缴纳（创建保证金）。 */
        PAY,
        /** 冻结。 */
        FREEZE,
        /** 退还（可能是扣除后的余额）。 */
        REFUND,
        /** 扣除（用于赔付）。 */
        DEDUCT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "deposit_id", nullable = false)
    private long depositId;

    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "amount", nullable = false, precision = 24, scale = 8)
    private BigDecimal amount;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "operator")
    private Long operator;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MerchantDepositRecord() {
    }

    public MerchantDepositRecord(long depositId, Action action, BigDecimal amount, String reason,
                                 Long operator, Instant createdAt) {
        this.depositId = depositId;
        this.action = action.name();
        this.amount = amount;
        this.reason = reason;
        this.operator = operator;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public long getDepositId() {
        return depositId;
    }

    public String getAction() {
        return action;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public Long getOperator() {
        return operator;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

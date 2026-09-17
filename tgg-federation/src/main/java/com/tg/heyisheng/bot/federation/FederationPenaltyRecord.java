package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.credit.PenaltyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 接收到的联邦处罚令（表 {@code federation_penalties}）。
 *
 * <p>{@code orderId} 是幂等键：同一 {@code order_id} 只处理一次，防重放与重复执行封禁。
 * 表结构由 Flyway V5 管理，JPA 侧 {@code ddl-auto: validate}。
 */
@Entity
@Table(name = "federation_penalties")
public class FederationPenaltyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private CreditSubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private long subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "penalty_type", nullable = false, length = 32)
    private PenaltyType penaltyType;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "signature", length = 256)
    private String signature;

    @Column(name = "origin_node", length = 512)
    private String originNode;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected FederationPenaltyRecord() {
    }

    public FederationPenaltyRecord(String orderId, CreditSubjectType subjectType, long subjectId,
                                   PenaltyType penaltyType, Instant issuedAt, String signature,
                                   String originNode, Instant receivedAt) {
        this.orderId = orderId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.penaltyType = penaltyType;
        this.issuedAt = issuedAt;
        this.signature = signature;
        this.originNode = originNode;
        this.receivedAt = receivedAt;
    }

    public Long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public CreditSubjectType getSubjectType() {
        return subjectType;
    }

    public long getSubjectId() {
        return subjectId;
    }

    public PenaltyType getPenaltyType() {
        return penaltyType;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public String getSignature() {
        return signature;
    }

    public String getOriginNode() {
        return originNode;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}

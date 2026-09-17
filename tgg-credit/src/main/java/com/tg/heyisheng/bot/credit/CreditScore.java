package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
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
 * 信用分账本行（三套分共用，按 {@link CreditSubjectType} 区分）。
 *
 * <p>表结构由 Flyway 管理（{@code V4__init_credit.sql}），JPA 侧 {@code ddl-auto: validate}
 * ——Hibernate 只校验、不改表。
 *
 * <p>用可变实体类而非 record：JPA 需要无参构造器与可变字段（与 {@code GroupConfig} 同因）。
 */
@Entity
@Table(name = "credit_scores")
public class CreditScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 用 STRING 映射存枚举名（可读、且加值不改存量数据）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private CreditSubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private long subjectId;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 要求的无参构造器（protected，避免业务代码误用）。 */
    protected CreditScore() {
    }

    public CreditScore(CreditSubjectType subjectType, long subjectId, int score, Instant updatedAt) {
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.score = score;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public CreditSubjectType getSubjectType() {
        return subjectType;
    }

    public long getSubjectId() {
        return subjectId;
    }

    public int getScore() {
        return score;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.core.credit.CreditEventType;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
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
 * 信用分流水行——一次分值变动的<b>不可变</b>记录（模块七 · §3.3）。
 *
 * <p><b>与 {@link CreditScore} 的分工</b>：{@code credit_scores} 是「现在多少分」的快照
 * （一行，UPDATE 覆盖），本表是「为什么变成这个分」的流水（每次变动一行，只追加）。
 * 两者缺一不可——只有快照则无法解释/回滚，只有流水则每次查询都要重算。
 *
 * <p><b>{@code idempotencyKey} 与 {@code CreditEvent.eventId} 不是一回事</b>：
 * 前者是<b>业务稳定标识</b>（同一条消息永远得到同一个键），用于去重；
 * 后者是事件实例 id（每次生成新 UUID），只适合审计追溯。用后者去重不起作用。
 *
 * <p>表结构由 Flyway 管理（{@code V16__init_credit_events.sql}），JPA 侧 {@code ddl-auto: validate}
 * ——Hibernate 只校验、不改表。用可变实体类而非 record：JPA 需要无参构造器与可变字段
 * （与 {@code CreditScore} / {@code GroupConfig} 同因）。
 */
@Entity
@Table(name = "credit_events")
public class CreditEventRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private CreditSubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private long subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private CreditEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 16)
    private RiskLevel severity;

    @Column(name = "hard_line", nullable = false)
    private boolean hardLine;

    @Column(name = "score_before", nullable = false)
    private int scoreBefore;

    @Column(name = "score_delta", nullable = false)
    private int scoreDelta;

    @Column(name = "score_after", nullable = false)
    private int scoreAfter;

    @Column(name = "source", length = 32)
    private String source;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /** JPA 要求的无参构造器（protected，避免业务代码误用）。 */
    protected CreditEventRecord() {
    }

    public CreditEventRecord(CreditSubjectType subjectType,
                             long subjectId,
                             CreditEventType eventType,
                             RiskLevel severity,
                             boolean hardLine,
                             int scoreBefore,
                             int scoreDelta,
                             int scoreAfter,
                             String source,
                             String idempotencyKey,
                             Instant occurredAt,
                             Instant recordedAt) {
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.eventType = eventType;
        this.severity = severity;
        this.hardLine = hardLine;
        this.scoreBefore = scoreBefore;
        this.scoreDelta = scoreDelta;
        this.scoreAfter = scoreAfter;
        this.source = source;
        this.idempotencyKey = idempotencyKey;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
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

    public CreditEventType getEventType() {
        return eventType;
    }

    public RiskLevel getSeverity() {
        return severity;
    }

    public boolean isHardLine() {
        return hardLine;
    }

    public int getScoreBefore() {
        return scoreBefore;
    }

    public int getScoreDelta() {
        return scoreDelta;
    }

    public int getScoreAfter() {
        return scoreAfter;
    }

    public String getSource() {
        return source;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}

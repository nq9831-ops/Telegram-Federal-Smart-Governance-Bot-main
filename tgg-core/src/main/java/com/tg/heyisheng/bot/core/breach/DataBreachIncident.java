package com.tg.heyisheng.bot.core.breach;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 数据泄露事件（模块十 §11.2）——72 小时通报的计时对象。
 *
 * <p><b>不含任何个人数据</b>：{@code scope} 只放范围描述（如「审计表导出接口被未授权访问」），
 * 不放受影响用户清单——那是通报材料的内容，不该进这张表。
 *
 * <p>表结构由 Flyway 管理（V14）。
 */
@Entity
@Table(name = "data_breach_incident")
public class DataBreachIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Column(name = "scope", nullable = false, length = 255)
    private String scope;

    @Column(name = "affected_count", nullable = false)
    private int affectedCount;

    @Column(name = "reported_at")
    private Instant reportedAt;

    @Column(name = "reported_by")
    private Long reportedBy;

    @Column(name = "registered_by")
    private Long registeredBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA 要求的无参构造器（protected，避免业务代码误用）。 */
    protected DataBreachIncident() {
    }

    public DataBreachIncident(Instant detectedAt, Instant deadlineAt, String scope,
                              int affectedCount, Long registeredBy, Instant createdAt) {
        this.detectedAt = detectedAt;
        this.deadlineAt = deadlineAt;
        this.scope = scope;
        this.affectedCount = affectedCount;
        this.registeredBy = registeredBy;
        this.createdAt = createdAt;
    }

    /** 标记已通报（幂等：重复标记不覆盖首次通报时间——那是合规证据）。 */
    public boolean markReported(Long operator, Instant at) {
        if (reportedAt != null) {
            return false;
        }
        this.reportedAt = at;
        this.reportedBy = operator;
        return true;
    }

    public boolean isReported() {
        return reportedAt != null;
    }

    /** 是否已过通报截止。 */
    public boolean isOverdue(Instant now) {
        return !isReported() && now.isAfter(deadlineAt);
    }

    public Long getId() {
        return id;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public String getScope() {
        return scope;
    }

    public int getAffectedCount() {
        return affectedCount;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    public Long getReportedBy() {
        return reportedBy;
    }

    public Long getRegisteredBy() {
        return registeredBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

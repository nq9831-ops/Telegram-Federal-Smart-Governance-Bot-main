package com.tg.heyisheng.bot.core.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 审计日志条目（模块十 §11.2）——<b>只追加</b>。
 *
 * <p>「不可修改 / 不可删除」由数据库触发器保证（见 V13 迁移），不依赖应用层自觉：
 * 只靠「仓库不提供 delete」挡不住运维直接执行 SQL，也挡不住将来有人加一个 deleteAll()。
 *
 * <p><b>不含消息正文</b>：{@code detail} 只放动作与结论。
 */
@Entity
@Table(name = "audit_log")
public class AuditEntry {

    /** 结果标识。 */
    public enum Outcome {
        /** 动作执行成功。 */
        SUCCESS,
        /** 动作抛出异常。 */
        FAILURE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "action", nullable = false, length = 128)
    private String action;

    @Column(name = "target")
    private Long target;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private Outcome outcome;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** JPA 要求的无参构造器（protected，避免业务代码误用）。 */
    protected AuditEntry() {
    }

    public AuditEntry(Long actorId, String action, Long target, Outcome outcome,
                      String detail, Instant occurredAt) {
        this.actorId = actorId;
        this.action = action;
        this.target = target;
        this.outcome = outcome;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public Long getTarget() {
        return target;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}

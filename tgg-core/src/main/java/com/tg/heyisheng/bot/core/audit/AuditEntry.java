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

    /**
     * 关联案件号（{@code moderation_review_queue.id}）；与审核无关的动作为 {@code null}。
     *
     * <p>让「发生了什么」可<b>按案件</b>聚合：后台案件详情页的时间线要按它取。
     * 不能拿 {@link #target} 当案件号——那个字段的语义是「chatId / 被判主体 id」，不唯一。
     */
    @Column(name = "case_id")
    private Long caseId;

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

    /** 兼容构造器：无案件号（命令路径、系统级动作、既有测试）。 */
    public AuditEntry(Long actorId, String action, Long target, Outcome outcome,
                      String detail, Instant occurredAt) {
        this(actorId, action, target, null, outcome, detail, occurredAt);
    }

    /**
     * 带案件号的构造器（审核路径）。
     *
     * <p><b>{@code actor_id} 在审核场景的语义</b>：填**被处置的用户**，而不是 {@code null}。
     * 理由：本表已有 {@code idx_audit_actor_time} 索引，且 {@code ExportMyDataCommandHandler}
     * 正是按 {@code actor_id} 导出「关于我自己的数据」——被处置记录属于当事人有权导出与查阅的范围，
     * 填 {@code null} 会让这条路径查不到。命令路径的 {@code actor_id} 是命令发起者，两者同构：
     * <b>都是「这条记录与哪个用户有关」</b>；只有纯系统级动作（无关联用户）才为 {@code null}。
     */
    public AuditEntry(Long actorId, String action, Long target, Long caseId, Outcome outcome,
                      String detail, Instant occurredAt) {
        this.actorId = actorId;
        this.action = action;
        this.target = target;
        this.caseId = caseId;
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

    /** 关联案件号；与审核无关的动作为 {@code null}。 */
    public Long getCaseId() {
        return caseId;
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

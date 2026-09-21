package com.tg.heyisheng.bot.core.audit;

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
 * 审计日志条目（模块十 §11.2）——<b>只追加</b>。
 *
 * <p>「不可修改 / 不可删除」由数据库层保证（见 V13 迁移注释与部署清单），不依赖应用层自觉：
 * 只靠「仓库不提供 delete」挡不住运维直接执行 SQL。
 *
 * <p><b>不含消息正文</b>：{@code detail} 只放动作与结论。
 *
 * <p><b>主体是 (类型, id) 二元组</b>：见 {@link ActorType}。历史行默认 {@link ActorType#TG_USER}
 * （V21 迁移的列默认值），应用侧新写入点一律显式带类型。
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

    /**
     * 主体类型；与 {@link #actorId} 共同构成「这条记录与哪个主体有关」。
     *
     * <p>非空，DB 侧默认 {@code TG_USER}（兜历史行）；应用侧写入点显式传值。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 16)
    private ActorType actorType;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private Outcome outcome;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** JPA 要求的无参构造器（protected，避免业务代码误用）。 */
    protected AuditEntry() {
    }

    /** 兼容构造器：无案件号、无显式主体类型（历史/命令路径，默认 {@link ActorType#TG_USER}）。 */
    public AuditEntry(Long actorId, String action, Long target, Outcome outcome,
                      String detail, Instant occurredAt) {
        this(ActorType.TG_USER, actorId, action, target, null, outcome, detail, occurredAt);
    }

    /** 兼容构造器：带案件号、无显式主体类型（默认 {@link ActorType#TG_USER}）。 */
    public AuditEntry(Long actorId, String action, Long target, Long caseId, Outcome outcome,
                      String detail, Instant occurredAt) {
        this(ActorType.TG_USER, actorId, action, target, caseId, outcome, detail, occurredAt);
    }

    /**
     * 完整构造器：显式主体类型（后台操作走 {@link ActorType#ADMIN_ACCOUNT}）。
     *
     * <p><b>{@code actor_id} 在审核场景的语义</b>：填**被处置的用户**，而不是 {@code null}。
     * 理由：本表已有按主体聚合的索引，且导出命令正是按主体取「关于我自己的数据」——
     * 被处置记录属于当事人有权导出与查阅的范围。命令路径的主体是发起者，两者同构：
     * <b>都是「这条记录与哪个主体有关」</b>；只有纯系统级动作（无关联主体）才为 {@code null}。
     */
    public AuditEntry(ActorType actorType, Long actorId, String action, Long target, Long caseId,
                      Outcome outcome, String detail, Instant occurredAt) {
        this.actorType = actorType;
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

    /** 主体类型；与 {@link #getActorId()} 共同定位主体。 */
    public ActorType getActorType() {
        return actorType;
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

package com.tg.heyisheng.bot.admin.dangerous;

import com.tg.heyisheng.bot.core.audit.ActorType;
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
 * 危险动作的双人复核请求——{@code dangerous_action_requests}。
 *
 * <p><b>双人 = 两个不同主体各表达一次意愿</b>：{@link #requestedById} 与 {@link #decidedById}
 * 必须不同（判定在服务层）。这是「不可逆对外动作」的护栏，与"前端弹两次确认"有本质区别
 * ——后者只有一个主体。
 *
 * <p>当前唯一动作是 {@link ActionType#RESTART}；超管不在此列（他直接执行，见迁移注释）。
 */
@Entity
@Table(name = "dangerous_action_requests")
public class DangerousActionRequest {

    /** 危险动作类型。 */
    public enum ActionType {
        /** 触发优雅重启（本系统唯一的不可逆对外动作）。 */
        RESTART
    }

    /** 复核状态。 */
    public enum Status {
        /** 待批准。 */
        PENDING,
        /** 已批准（且已执行）。 */
        APPROVED,
        /** 已拒绝。 */
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 32)
    private ActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_by_type", nullable = false, length = 16)
    private ActorType requestedByType;

    @Column(name = "requested_by_id", nullable = false)
    private Long requestedById;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "decided_by_type", length = 16)
    private ActorType decidedByType;

    @Column(name = "decided_by_id")
    private Long decidedById;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "note", length = 255)
    private String note;

    /** JPA 要求的无参构造器。 */
    protected DangerousActionRequest() {
    }

    /** 发起一条待批准请求。 */
    public DangerousActionRequest(ActionType actionType, ActorType requestedByType, Long requestedById,
                                  Instant createdAt) {
        this.actionType = actionType;
        this.requestedByType = requestedByType;
        this.requestedById = requestedById;
        this.status = Status.PENDING;
        this.createdAt = createdAt;
    }

    /** 是否待批准。 */
    public boolean isPending() {
        return status == Status.PENDING;
    }

    /** 是否为同一主体（双人校验用：发起人不得自批）。 */
    public boolean wasRequestedBy(ActorType subjectType, Long subjectId) {
        return requestedByType == subjectType && requestedById != null && requestedById.equals(subjectId);
    }

    /** 批准。 */
    public void approve(ActorType byType, Long byId, String note, Instant now) {
        this.status = Status.APPROVED;
        this.decidedByType = byType;
        this.decidedById = byId;
        this.note = note;
        this.decidedAt = now;
    }

    /** 拒绝。 */
    public void reject(ActorType byType, Long byId, String note, Instant now) {
        this.status = Status.REJECTED;
        this.decidedByType = byType;
        this.decidedById = byId;
        this.note = note;
        this.decidedAt = now;
    }

    public Long getId() {
        return id;
    }

    public ActionType getActionType() {
        return actionType;
    }

    public ActorType getRequestedByType() {
        return requestedByType;
    }

    public Long getRequestedById() {
        return requestedById;
    }

    public Status getStatus() {
        return status;
    }

    public ActorType getDecidedByType() {
        return decidedByType;
    }

    public Long getDecidedById() {
        return decidedById;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getNote() {
        return note;
    }
}

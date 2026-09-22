package com.tg.heyisheng.bot.federation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 联邦申诉（表 {@code federation_appeals}）：用户提交 → 入待审队列 → 联邦管理员裁定。
 *
 * <p>{@code appealText} 是用户**主动提交**的申诉正文，不是被监听的对话内容，
 * 与「消息原文零存储」不冲突。
 */
@Entity
@Table(name = "federation_appeals")
public class FederationAppeal {

    /** 申诉状态。 */
    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "appeal_type", nullable = false, length = 32)
    private String appealType;

    @Column(name = "appeal_text")
    private String appealText;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FederationAppeal() {
    }

    public FederationAppeal(Long userId, String appealType, String appealText, Instant createdAt) {
        this.userId = userId;
        this.appealType = appealType;
        this.appealText = appealText;
        this.status = Status.PENDING.name();
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getAppealType() {
        return appealType;
    }

    public String getAppealText() {
        return appealText;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 是否已结案（终态）：APPROVED / REJECTED。 */
    public boolean isDecided() {
        return !Status.PENDING.name().equals(status);
    }

    /**
     * 裁定：APPROVED / REJECTED。
     *
     * <p>状态机自守：仅 {@code PENDING} 可被裁定。已结案的申诉是**终态**，
     * 再裁定会静默覆盖前次结论（审计断裂），故在这一层直接拒绝——
     * 即便将来有新的调用方绕过服务层的守卫，实体也不会被二次改写。
     *
     * @throws IllegalStateException 该申诉已结案
     */
    public void decide(Status decision) {
        if (isDecided()) {
            throw new IllegalStateException("申诉 #" + id + " 已结案（" + status + "），不能再次裁定");
        }
        this.status = decision.name();
    }
}

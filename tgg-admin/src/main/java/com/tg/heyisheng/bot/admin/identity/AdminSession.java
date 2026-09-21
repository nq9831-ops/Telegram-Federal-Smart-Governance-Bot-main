package com.tg.heyisheng.bot.admin.identity;

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
 * 后台会话——{@code admin_sessions}。
 *
 * <p><b>服务端会话而非 JWT</b>：用户要求「全部掌控」⇒ 改权限/封号必须<b>立刻生效</b>。
 * JWT 无法吊销，封了号旧 token 仍有效直到过期；服务端会话可即时 {@link #revoke}。
 *
 * <p><b>只存令牌哈希</b>：明文令牌只在登录响应里给一次，库泄露也无法据此反推会话。
 *
 * <p><b>主体是 (类型, id) 二元组</b>：{@link ActorType#ADMIN_ACCOUNT} 时 {@code subjectId} 是账号 id；
 * {@link ActorType#TG_USER} 时是 TG userId（TG 登录，后续波）。两者数值空间重叠，故必须带类型。
 */
@Entity
@Table(name = "admin_sessions")
public class AdminSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "token_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private ActorType subjectType;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** JPA 要求的无参构造器。 */
    protected AdminSession() {
    }

    /** 签发一条新会话。 */
    public AdminSession(String tokenHash, ActorType subjectType, Long subjectId,
                        Instant createdAt, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** 在给定时刻是否可用（未吊销且未过期）。 */
    public boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    /** 立即吊销（强制下线 / 登出）。 */
    public void revoke(Instant now) {
        this.revokedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public ActorType getSubjectType() {
        return subjectType;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}

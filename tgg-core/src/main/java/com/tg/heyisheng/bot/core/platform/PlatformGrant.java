package com.tg.heyisheng.bot.core.platform;

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
 * 一条平台能力授权——{@code platform_grants}。
 *
 * <p>主体是 {@code (subjectType, subjectId)} 二元组（理由见迁移注释与 {@link ActorType}）。
 */
@Entity
@Table(name = "platform_grants")
public class PlatformGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private ActorType subjectType;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 32)
    private PlatformPermission permission;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "granted_by")
    private Long grantedBy;

    /** JPA 要求的无参构造器。 */
    protected PlatformGrant() {
    }

    public PlatformGrant(ActorType subjectType, Long subjectId, PlatformPermission permission,
                         Long grantedBy, Instant grantedAt) {
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.permission = permission;
        this.grantedBy = grantedBy;
        this.grantedAt = grantedAt;
    }

    public Long getId() {
        return id;
    }

    public ActorType getSubjectType() {
        return subjectType;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public PlatformPermission getPermission() {
        return permission;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Long getGrantedBy() {
        return grantedBy;
    }
}

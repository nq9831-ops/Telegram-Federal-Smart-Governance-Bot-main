package com.tg.heyisheng.bot.listing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 群组收录条目（表 {@code listing_groups}，模块五）。
 *
 * <p>字段与设计文档 §5 的 {@code listing_groups} 表严格对应。表结构由 Flyway V6 管理，
 * JPA 侧 {@code ddl-auto: validate}。
 *
 * <p><b>软删不物理删</b>：连续失败判失效时把 {@link #status} 置 {@link Status#SUSPENDED}，
 * 记录保留以便审计与申诉，绝不物理删除。
 *
 * <p>{@code chatId} 为唯一键：同群重复提交走 {@code INSERT IGNORE} 幂等（沿用坑 21 纪律）。
 * {@code submitterUserId} 存明文（与 {@code credit_scores} 同口径：定位提交者、投递通知需定位主体）。
 */
@Entity
@Table(name = "listing_groups")
public class ListingGroup {

    /** 收录状态：{@code ACTIVE} 有效；{@code SUSPENDED} 已软删。 */
    public enum Status { ACTIVE, SUSPENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private long chatId;

    @Column(name = "invite_link", length = 255)
    private String inviteLink;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "submitter_user_id")
    private Long submitterUserId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "fail_count", nullable = false)
    private int failCount;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ListingGroup() {
    }

    public ListingGroup(long chatId, String inviteLink, String title, Long submitterUserId,
                        Instant createdAt) {
        this.chatId = chatId;
        this.inviteLink = inviteLink;
        this.title = title;
        this.submitterUserId = submitterUserId;
        this.status = Status.ACTIVE.name();
        this.failCount = 0;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public long getChatId() {
        return chatId;
    }

    public String getInviteLink() {
        return inviteLink;
    }

    public String getTitle() {
        return title;
    }

    public Long getSubmitterUserId() {
        return submitterUserId;
    }

    public String getStatus() {
        return status;
    }

    public int getFailCount() {
        return failCount;
    }

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public Instant getSuspendedAt() {
        return suspendedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

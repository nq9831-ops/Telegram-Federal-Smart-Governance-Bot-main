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

    /**
     * 验证成功：刷新最后验证时间并清零失败计数。
     *
     * <p>刻意<b>不</b>顺手把状态改回 ACTIVE——本方法只服务于「仍在 ACTIVE 的条目」，
     * 让已被判失效的条目复活必须走申诉流程，不能由一次探测结果代劳。
     */
    public void markVerifiedOk(Instant now) {
        this.failCount = 0;
        this.lastVerifiedAt = now;
        this.updatedAt = now;
    }

    /**
     * 记录一次「真失效」：累加失败计数；达到阈值则置 {@link Status#SUSPENDED}（软删）。
     *
     * <p><b>只有 FAIL 能走到这里</b>——探测失败（ERROR）由服务层拦截，不得调用本方法，
     * 否则一次网络抖动就会把好群推向失效（本模块最危险的失败模式）。
     *
     * @return {@code true} = 本次到达阈值、已软删
     */
    public boolean registerFailure(Instant now, int failThreshold) {
        this.failCount++;
        this.updatedAt = now;
        if (this.failCount >= failThreshold) {
            this.status = Status.SUSPENDED.name();
            this.suspendedAt = now;
            return true;
        }
        return false;
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

package com.tg.heyisheng.bot.core.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 审核案件的**当事人申诉**（模块九 §10.4 的申诉侧）。
 *
 * <p><b>为什么与案件分表</b>：案件队列是**系统写的审核产物**，申诉是**用户写的当事人输入**。
 * 混表会让「谁能写哪几列」这条边界消失——而这条边界正是本表最关键的安全属性。
 *
 * <p><b>安全边界（唯一但必须死守）</b>：{@link #userId} 必须是该案件
 * （{@code moderation_review_queue.user_id}）的当事人。案件号在群内公开
 * （删除告知里带着编号），所以**知道编号 ≠ 有权申诉**——校验在写路径上做。
 *
 * <p><b>不含消息正文</b>：{@link #reason} 是用户主动提交的申诉理由，不是被监听的对话内容；
 * 它落库、不进日志、不进审核判定（与 {@code federation_appeals.appeal_text} 同一口径）。
 */
@Entity
@Table(name = "moderation_case_appeals")
public class CaseAppeal {

    /** 申诉状态：提交即待处理。状态机（认领 / 补充材料）待后台需要时再扩，见原文 §2.3。 */
    public static final String STATUS_PENDING = "PENDING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被申诉的审核案件号（{@code moderation_review_queue.id}）。 */
    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    /** 申诉人；必须等于该案件的当事人。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA 需要的无参构造器。 */
    protected CaseAppeal() {
    }

    public CaseAppeal(Long reviewId, Long userId, String reason, Instant createdAt) {
        this.reviewId = reviewId;
        this.userId = userId;
        this.reason = reason;
        this.createdAt = createdAt;
        this.status = STATUS_PENDING;
    }

    public Long getId() {
        return id;
    }

    public Long getReviewId() {
        return reviewId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

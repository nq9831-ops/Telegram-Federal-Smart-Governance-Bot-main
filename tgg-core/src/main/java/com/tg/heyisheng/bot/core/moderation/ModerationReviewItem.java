package com.tg.heyisheng.bot.core.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

/**
 * 待人工复核的审核命中记录。
 *
 * <p><b>刻意不含消息正文</b>：本表只承载「判定结论 + 定位信息」。
 * V5.0 要求违规片段脱敏存储，项目定位隐私优先——正文由 {@code MessageScrubber} 清除，
 * 绝不进入本表。
 *
 * <p>表结构由 Flyway 管理（{@code V2__init_moderation_review_queue.sql}），
 * JPA 侧 {@code ddl-auto: validate}——Hibernate 只校验、不改表。
 */
@Entity
@Table(name = "moderation_review_queue")
public class ModerationReviewItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 群组 ID。Telegram 群 ID 为负数且可超出 INT 范围。 */
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    /** 发布者 ID；频道帖等场景可能缺失。 */
    @Column(name = "user_id")
    private Long userId;

    /** 触发本次判定的消息 ID；服务类更新可能缺失。 */
    @Column(name = "message_id")
    private Integer messageId;

    /** 命中的规则 id，逗号分隔——是「结论」而非「内容」，不含正文。 */
    @Column(name = "rule_ids", nullable = false)
    private String ruleIds;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 16)
    private RiskLevel riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReviewStatus status = ReviewStatus.PENDING;

    /** 是否命中硬红线（命中即自动封禁；操作员可推翻 → 解封）。 */
    @Column(name = "hard_line", nullable = false)
    private boolean hardLine;

    /** 裁决人 userId（全局白名单）；未裁决为 null。 */
    @Column(name = "decided_by")
    private Long decidedBy;

    /** 裁决时间；未裁决为 null。 */
    @Column(name = "decided_at")
    private Instant decidedAt;

    /** 裁决备注（<b>不含消息正文</b>）。 */
    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 要求的无参构造器（protected，避免业务代码误用）。 */
    protected ModerationReviewItem() {
    }

    public ModerationReviewItem(Long chatId, Long userId, Integer messageId,
                                List<String> ruleIds, RiskLevel riskLevel) {
        this(chatId, userId, messageId, ruleIds, riskLevel, false);
    }

    public ModerationReviewItem(Long chatId, Long userId, Integer messageId,
                                List<String> ruleIds, RiskLevel riskLevel, boolean hardLine) {
        this.chatId = chatId;
        this.userId = userId;
        this.messageId = messageId;
        this.ruleIds = ruleIds == null ? "" : String.join(",", ruleIds);
        this.riskLevel = riskLevel;
        this.hardLine = hardLine;
        this.status = ReviewStatus.PENDING;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 写入裁决结论（操作员推翻权的落点）。
     *
     * <p><b>幂等不在此处保证</b>：实体只负责「把结论写进去」，「已终态不可再改」的判定在服务层
     * （{@code ModerationReviewDecisionService}）——并发语义不该由实体承担。
     *
     * @param decision 只能是 {@link ReviewStatus#APPROVED}（维持）或 {@link ReviewStatus#REJECTED}（推翻）
     */
    public void decide(ReviewStatus decision, Long operator, String note, Instant decidedAt) {
        if (decision != ReviewStatus.APPROVED && decision != ReviewStatus.REJECTED) {
            throw new IllegalArgumentException("裁决结论只能是 APPROVED 或 REJECTED");
        }
        this.status = decision;
        this.decidedBy = operator;
        this.note = note;
        this.decidedAt = decidedAt;
    }

    /** 是否仍待裁决（{@link ReviewStatus#PENDING}）。 */
    public boolean isPending() {
        return status == ReviewStatus.PENDING;
    }

    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getChatId() {
        return chatId;
    }

    public Long getUserId() {
        return userId;
    }

    public Integer getMessageId() {
        return messageId;
    }

    public String getRuleIds() {
        return ruleIds;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public boolean isHardLine() {
        return hardLine;
    }

    public Long getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

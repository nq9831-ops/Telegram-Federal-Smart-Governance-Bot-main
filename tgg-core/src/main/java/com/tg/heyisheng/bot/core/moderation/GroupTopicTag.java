package com.tg.heyisheng.bot.core.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 群组「话题标签」——敏感话题分级的**豁免依据**（模块九 §10.5）。
 *
 * <p>一个群可声明多个标签（一对多）；每行带 {@code createdBy} / {@code createdAt}（可审计）；
 * 撤销单个标签就是删一行（可单独增删）。表结构由 Flyway 管理（V10），JPA 侧 {@code ddl-auto: validate}。
 *
 * <p><b>只存标签本身，不存任何消息内容</b>——与全项目的隐私口径一致。
 */
@Entity
@Table(name = "group_topic_tags")
public class GroupTopicTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    /** 小写规范化后的标签（gambling / adult / politics…）。 */
    @Column(name = "tag", nullable = false, length = 32)
    private String tag;

    /** 添加人 userId（可能缺失）。 */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA 要求的无参构造器（protected，避免业务代码误用）。 */
    protected GroupTopicTag() {
    }

    public GroupTopicTag(Long chatId, String tag, Long createdBy, Instant createdAt) {
        this.chatId = chatId;
        this.tag = tag;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getChatId() {
        return chatId;
    }

    public String getTag() {
        return tag;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

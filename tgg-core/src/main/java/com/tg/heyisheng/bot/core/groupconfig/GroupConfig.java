package com.tg.heyisheng.bot.core.groupconfig;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 群组级配置。
 *
 * <p>表结构由 Flyway 管理（{@code V1__init_group_configs.sql}），
 * JPA 侧配置为 {@code ddl-auto: validate}——Hibernate 只校验、不改表。
 */
@Entity
@Table(name = "group_configs")
public class GroupConfig {

    /** 群组 ID。Telegram 的群/超级群 ID 为负数且可超出 INT 范围。 */
    @Id
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "title")
    private String title;

    /** 功能总开关。关闭时该群的自动化能力应整体停用。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 要求的无参构造器（protected，避免业务代码误用）。 */
    protected GroupConfig() {
    }

    public GroupConfig(Long chatId, String title) {
        this.chatId = chatId;
        this.title = title;
        this.enabled = true;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

    public Long getChatId() {
        return chatId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package com.tg.heyisheng.bot.core.notify;

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
 * 免打扰时段内暂存的通知（模块十 §11.1）——时段结束后由调度器冲刷。
 *
 * <p><b>为什么落库</b>：内存队列在重启时<b>静默丢失</b>，用户永远不知道自己错过了什么；
 * 落库后重启不丢，冲刷前可审计。这是本项目对「不静默」的一贯取舍。
 *
 * <p>表结构由 Flyway 管理（V12）。
 */
@Entity
@Table(name = "notification_deferred")
public class DeferredNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 16)
    private NotificationLevel level;

    /** 通知正文（由生产侧拼装，**不含用户消息原文**）。 */
    @Column(name = "text", nullable = false, length = 1000)
    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA 要求的无参构造器（protected，避免业务代码误用）。 */
    protected DeferredNotification() {
    }

    public DeferredNotification(long userId, NotificationLevel level, String text, Instant createdAt) {
        this.userId = userId;
        this.level = level;
        this.text = text;
        this.createdAt = createdAt;
    }

    /** 还原成可投递的通知。 */
    public Notification toNotification() {
        return new Notification(level, userId, text);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public NotificationLevel getLevel() {
        return level;
    }

    public String getText() {
        return text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

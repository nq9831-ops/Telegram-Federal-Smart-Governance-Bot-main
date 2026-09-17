package com.tg.heyisheng.bot.core.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalTime;

/**
 * 用户通知偏好（模块十 §11.1）：目前只有「免打扰时段」。
 *
 * <p><b>未配置 = 不设免打扰</b>：沉默不擅自替用户消音——压住通知比多发一条更容易造成伤害
 * （用户可能正等着封禁/解封这类结果）。
 *
 * <p>表结构由 Flyway 管理（V12），JPA 侧 {@code ddl-auto: validate}。
 */
@Entity
@Table(name = "notification_preferences")
public class NotificationPreference {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "quiet_start")
    private LocalTime quietStart;

    @Column(name = "quiet_end")
    private LocalTime quietEnd;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 要求的无参构造器（protected，避免业务代码误用）。 */
    protected NotificationPreference() {
    }

    public NotificationPreference(Long userId, Instant updatedAt) {
        this.userId = userId;
        this.updatedAt = updatedAt;
    }

    /** 设置（或覆盖）免打扰时段。 */
    public void setQuietHours(QuietHours hours, Instant at) {
        this.quietStart = hours == null ? null : hours.start();
        this.quietEnd = hours == null ? null : hours.end();
        this.updatedAt = at;
    }

    /** 已配置的时段；未配置返回 {@code null}。 */
    public QuietHours quietHours() {
        return (quietStart == null || quietEnd == null) ? null : new QuietHours(quietStart, quietEnd);
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

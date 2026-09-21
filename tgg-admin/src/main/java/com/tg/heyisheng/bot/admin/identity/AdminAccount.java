package com.tg.heyisheng.bot.admin.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

/**
 * 后台账号（超管 / 操作员）——{@code admin_accounts}。
 *
 * <p><b>密码只存哈希</b>（{@link PasswordHasher}），实体不持有明文。
 *
 * <p><b>锁定</b>：连续登录失败达阈值即写入 {@code locked_until}（基本锁定的字段在此落地，
 * 限流/TOTP 增强在护栏波补全）。
 */
@Entity
@Table(name = "admin_accounts")
public class AdminAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private AdminRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AdminStatus status;

    @Column(name = "totp_secret", length = 64)
    private String totpSecret;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 要求的无参构造器。 */
    protected AdminAccount() {
    }

    /** 新建账号（状态 ACTIVE、失败计数 0）。 */
    public AdminAccount(String username, String passwordHash, AdminRole role, Instant now) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = AdminStatus.ACTIVE;
        this.failedAttempts = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 是否超管。 */
    public boolean isSuperAdmin() {
        return role == AdminRole.SUPER_ADMIN;
    }

    /** 是否处于可用状态（ACTIVE）。 */
    public boolean isActive() {
        return status == AdminStatus.ACTIVE;
    }

    /** 在给定时刻是否被锁定。 */
    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** 停用（调用方须同时吊销其全部会话）。 */
    public void disable(Instant now) {
        this.status = AdminStatus.DISABLED;
        this.updatedAt = now;
    }

    /** 启用。 */
    public void enable(Instant now) {
        this.status = AdminStatus.ACTIVE;
        this.updatedAt = now;
    }

    /** 重置密码（传哈希）。 */
    public void changePasswordHash(String newHash, Instant now) {
        this.passwordHash = newHash;
        this.updatedAt = now;
    }

    /** 记一次登录失败；达阈值即锁定 {@code lockFor}。 */
    public void recordFailedAttempt(int maxFailed, Duration lockFor, Instant now) {
        this.failedAttempts++;
        this.updatedAt = now;
        if (failedAttempts >= maxFailed) {
            this.lockedUntil = now.plus(lockFor);
        }
    }

    /** 登录成功后清零失败计数与锁定。 */
    public void resetFailedAttempts(Instant now) {
        this.failedAttempts = 0;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    /** 设置的 TOTP 密钥（Base32）；为空表示未启用第二因子。 */
    public String getTotpSecret() {
        return totpSecret;
    }

    /** 启用 / 更换 TOTP 密钥。 */
    public void setTotpSecret(String secret, Instant now) {
        this.totpSecret = secret;
        this.updatedAt = now;
    }

    /** 是否已启用 TOTP。 */
    public boolean hasTotp() {
        return totpSecret != null && !totpSecret.isBlank();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public AdminRole getRole() {
        return role;
    }

    public AdminStatus getStatus() {
        return status;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

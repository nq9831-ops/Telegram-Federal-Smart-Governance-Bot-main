package com.tg.heyisheng.bot.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模块十一（Web 后台）配置。
 *
 * <p><b>默认不装配</b>：{@code tgg.admin.api-token} 为空时整个模块的端点不产生
 * （见 {@link AdminConfiguration} 的条件），与项目「未启用即零影响」的契约一致。
 *
 * <p><b>api-token 的角色变化</b>：引入账号体系后，请求鉴权已改为<b>服务端会话</b>
 * （{@code Authorization: Bearer <会话令牌>} → {@code admin_sessions}），不再用共享 token。
 * 但 api-token 仍保留为<b>模块装配开关</b>——「一个连凭证都没配的后台，不该在公网上存在」。
 */
@ConfigurationProperties(prefix = "tgg.admin")
public class AdminProperties {

    /** 后台启用开关：为空则 /admin/* 端点整体不装配（见 {@code AdminApiTokenCondition}）。 */
    private String apiToken = "";

    /** 待办超过该小时数 → 提醒（模块十一 §12.1 第 4 步）。 */
    private int overdueRemindHours = 24;

    /** 待办超过该小时数 → 升级提醒（同上）。 */
    private int overdueEscalateHours = 72;

    // ───────────────────────── 账号体系（模块十一 · 账号与权限模型）─────────────────────────

    /** 超管登录名（引导创建用）。空 = 不引导创建超管（无任何 API 可建超管）。 */
    private String superUser = "";

    /** 超管密码哈希（{@code PasswordHasher} 的 {@code pbkdf2$...} 串）。空 = 不引导创建超管。 */
    private String superPasswordHash = "";

    /** Telegram 登录 Widget 的 bot username（前端 {@code data-telegram-login} 用；不含 @）。空则不展示。 */
    private String tgLoginBotUsername = "";

    /** TG 登录签名的时效（小时）。 */
    private int tgLoginMaxAgeHours = 1;

    /** 会话有效期（小时）。 */
    private int sessionTtlHours = 12;

    /** 连续登录失败达该次数即锁定账号（护栏波启用计数）。 */
    private int loginMaxFailedAttempts = 5;

    /** 锁定时长（分钟）。 */
    private int loginLockMinutes = 15;

    /** 每来源 IP 每分钟允许的登录尝试次数（防跨账号爆破）。 */
    private int loginMaxPerMinute = 20;

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public int getOverdueRemindHours() {
        return overdueRemindHours;
    }

    public void setOverdueRemindHours(int overdueRemindHours) {
        this.overdueRemindHours = overdueRemindHours;
    }

    public int getOverdueEscalateHours() {
        return overdueEscalateHours;
    }

    public void setOverdueEscalateHours(int overdueEscalateHours) {
        this.overdueEscalateHours = overdueEscalateHours;
    }

    public String getSuperUser() {
        return superUser;
    }

    public void setSuperUser(String superUser) {
        this.superUser = superUser;
    }

    public String getSuperPasswordHash() {
        return superPasswordHash;
    }

    public void setSuperPasswordHash(String superPasswordHash) {
        this.superPasswordHash = superPasswordHash;
    }

    public int getSessionTtlHours() {
        return sessionTtlHours;
    }

    public String getTgLoginBotUsername() {
        return tgLoginBotUsername;
    }

    public void setTgLoginBotUsername(String tgLoginBotUsername) {
        this.tgLoginBotUsername = tgLoginBotUsername;
    }

    public int getTgLoginMaxAgeHours() {
        return tgLoginMaxAgeHours;
    }

    public void setTgLoginMaxAgeHours(int tgLoginMaxAgeHours) {
        this.tgLoginMaxAgeHours = tgLoginMaxAgeHours;
    }

    public void setSessionTtlHours(int sessionTtlHours) {
        this.sessionTtlHours = sessionTtlHours;
    }

    public int getLoginMaxFailedAttempts() {
        return loginMaxFailedAttempts;
    }

    public void setLoginMaxFailedAttempts(int loginMaxFailedAttempts) {
        this.loginMaxFailedAttempts = loginMaxFailedAttempts;
    }

    public int getLoginLockMinutes() {
        return loginLockMinutes;
    }

    public int getLoginMaxPerMinute() {
        return loginMaxPerMinute;
    }

    public void setLoginMaxPerMinute(int loginMaxPerMinute) {
        this.loginMaxPerMinute = loginMaxPerMinute;
    }

    public void setLoginLockMinutes(int loginLockMinutes) {
        this.loginLockMinutes = loginLockMinutes;
    }
}

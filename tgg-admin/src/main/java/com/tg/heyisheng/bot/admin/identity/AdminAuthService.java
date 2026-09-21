package com.tg.heyisheng.bot.admin.identity;

import com.tg.heyisheng.bot.core.audit.ActorType;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 后台认证服务——登录、会话校验、登出、强制下线。
 *
 * <p><b>服务端会话</b>：登录签发一条 {@link AdminSession}（库中只存令牌哈希），
 * 校验时按哈希反查。用户要求「对系统全部掌控」⇒ 权限变更/停用必须<b>立刻生效</b>，
 * 故用可即时吊销的服务端会话而非 JWT。
 *
 * <p><b>防用户名枚举</b>：用户名不存在、账号停用/锁定、密码错误三种情形对外一视同仁（都返回
 * {@link Optional#empty()}），且<b>都执行一次同代价的哈希运算</b>——否则「用户不存在」会因提前返回
 * 而明显更快，攻击者可据此枚举有效用户名。
 *
 * <p><b>失败锁定</b>：密码错误即累计 {@code failed_attempts}，达阈值写 {@code locked_until}；
 * 被锁定的账号即使密码正确也不签发会话（{@code isLocked} 前置判定）。阈值与时长由构造参数注入。
 */
public class AdminAuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 与真实哈希同格式、同迭代数的占位哈希——用于用户名不存在时耗时代偿。 */
    private static final String DUMMY_HASH = PasswordHasher.hash("timing-compensation-placeholder");

    /** 通过认证的主体：类型 + id + 角色（TG 用户无本地角色，role 为 null）。 */
    public record Authenticated(ActorType subjectType, Long subjectId, AdminRole role) {
    }

    /** 登录结果：明文令牌（只在响应里给一次）+ 主体。 */
    public record LoginResult(String token, Authenticated subject) {
    }

    private final AdminAccountRepository accounts;
    private final AdminSessionRepository sessions;
    private final Clock clock;
    private final int maxFailedAttempts;
    private final Duration lockDuration;

    public AdminAuthService(AdminAccountRepository accounts, AdminSessionRepository sessions, Clock clock,
                            int maxFailedAttempts, Duration lockDuration) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.clock = clock;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockDuration = lockDuration;
    }

    /**
     * 登录：校验凭据并签发会话。
     *
     * @return 成功时含明文令牌与主体；失败（用户名不存在 / 停用 / 锁定 / 密码错误）一律为空
     */
    /** 登录（无第二因子）——兼容重载。 */
    @Transactional
    public Optional<LoginResult> login(String username, String rawPassword, Duration ttl) {
        return login(username, rawPassword, null, ttl);
    }

    /**
     * 登录：校验凭据（+ 可选 TOTP）并签发会话。
     *
     * @param totpCode 若账号已启用 TOTP，则此项必须为有效的 6 位码；未启用时忽略
     */
    @Transactional
    public Optional<LoginResult> login(String username, String rawPassword, String totpCode, Duration ttl) {
        Instant now = clock.instant();
        String name = username == null ? "" : username.trim();
        Optional<AdminAccount> found = name.isEmpty() ? Optional.empty() : accounts.findByUsername(name);
        if (found.isEmpty()) {
            PasswordHasher.matches(rawPassword, DUMMY_HASH);   // 耗时代偿，防枚举
            return Optional.empty();
        }
        AdminAccount account = found.get();
        if (!account.isActive() || account.isLocked(now)) {
            PasswordHasher.matches(rawPassword, DUMMY_HASH);
            return Optional.empty();
        }
        if (!PasswordHasher.matches(rawPassword, account.getPasswordHash())) {
            // 记一次失败并在达阈值时锁定：下一次登录即被上面的 isLocked 拦下
            account.recordFailedAttempt(maxFailedAttempts, lockDuration, now);
            accounts.save(account);
            return Optional.empty();
        }
        // 第二因子：已启用 TOTP 的账号必须提供有效码（密码对了也不够）
        if (account.hasTotp() && !TotpGenerator.verify(account.getTotpSecret(), totpCode, now)) {
            return Optional.empty();
        }
        account.resetFailedAttempts(now);
        accounts.save(account);

        String token = newToken();
        sessions.save(new AdminSession(hashToken(token), ActorType.ADMIN_ACCOUNT,
                account.getId(), now, now.plus(ttl)));
        return Optional.of(new LoginResult(token,
                new Authenticated(ActorType.ADMIN_ACCOUNT, account.getId(), account.getRole())));
    }

    /**
     * TG 用户登录：验签由 {@link TelegramLoginVerifier} 完成，此处只签发会话（<b>不建本地账号</b>）。
     */
    @Transactional
    public LoginResult loginAsTelegramUser(Long tgUserId, Duration ttl) {
        Instant now = clock.instant();
        String token = newToken();
        sessions.save(new AdminSession(hashToken(token), ActorType.TG_USER, tgUserId, now, now.plus(ttl)));
        return new LoginResult(token, new Authenticated(ActorType.TG_USER, tgUserId, null));
    }

    /**
     * 校验会话令牌，返回主体。
     *
     * <p>每次校验都即时读会话与账号状态——故「停用账号」「强制下线」立刻生效（无需等过期）。
     */
    @Transactional(readOnly = true)
    public Optional<Authenticated> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<AdminSession> found = sessions.findByTokenHash(hashToken(rawToken));
        if (found.isEmpty() || !found.get().isUsable(now)) {
            return Optional.empty();
        }
        AdminSession session = found.get();
        if (session.getSubjectType() == ActorType.ADMIN_ACCOUNT) {
            return accounts.findById(session.getSubjectId())
                    .filter(AdminAccount::isActive)
                    .filter(a -> !a.isLocked(now))
                    .map(a -> new Authenticated(ActorType.ADMIN_ACCOUNT, a.getId(), a.getRole()));
        }
        // TG_USER：会话由 TG 登录签发（后续波）；此处按会话主体直接返回
        return Optional.of(new Authenticated(ActorType.TG_USER, session.getSubjectId(), null));
    }

    /** 登出：吊销该令牌对应的会话（幂等——不存在也不报错）。 */
    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        sessions.findByTokenHash(hashToken(rawToken)).ifPresent(session -> {
            session.revoke(now);
            sessions.save(session);
        });
    }

    /** 吊销某主体的全部会话（停用账号 / 强制下线）。返回被吊销的会话数。 */
    @Transactional
    public int revokeAllFor(ActorType subjectType, Long subjectId) {
        Instant now = clock.instant();
        int revoked = 0;
        for (AdminSession session : sessions.findBySubjectTypeAndSubjectId(subjectType, subjectId)) {
            if (session.isUsable(now)) {
                session.revoke(now);
                sessions.save(session);
                revoked++;
            }
        }
        return revoked;
    }

    /** 生成会话令牌（256 bit 随机，URL-safe）。 */
    private static String newToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** 令牌 → SHA-256 hex（库中只存哈希）。 */
    static String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("会话令牌哈希失败", ex);
        }
    }
}

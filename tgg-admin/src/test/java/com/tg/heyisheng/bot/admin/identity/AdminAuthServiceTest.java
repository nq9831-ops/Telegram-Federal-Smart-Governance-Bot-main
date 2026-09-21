package com.tg.heyisheng.bot.admin.identity;

import com.tg.heyisheng.bot.core.audit.ActorType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证服务（登录 / 会话校验 / 登出）。
 *
 * <p>守的重点：<b>停用账号的既有会话必须即时失效</b>——这正是用服务端会话而非 JWT 的理由
 * （用户要求「全部掌控」，封号要立刻生效）。以及：失败一律返回空、不签发会话。
 */
class AdminAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");
    private static final Duration TTL = Duration.ofHours(12);
    private static final long ACCOUNT_ID = 5L;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AdminAccountRepository accounts = mock(AdminAccountRepository.class);
    private final AdminSessionRepository sessions = mock(AdminSessionRepository.class);
    private final AdminAuthService service =
            new AdminAuthService(accounts, sessions, clock, 5, Duration.ofMinutes(15));

    private AdminAccount account(AdminRole role, String password) {
        AdminAccount a = new AdminAccount("root", PasswordHasher.hash(password), role, NOW);
        set(a, "id", ACCOUNT_ID);
        return a;
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = AdminAccount.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("测试夹具无法写入字段 " + field, ex);
        }
    }

    @Test
    void loginSucceedsAndSignsASession() {
        when(accounts.findByUsername("root")).thenReturn(Optional.of(account(AdminRole.SUPER_ADMIN, "pw")));
        when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<AdminAuthService.LoginResult> r = service.login("root", "pw", TTL);

        assertThat(r).isPresent();
        assertThat(r.get().token()).isNotBlank();
        assertThat(r.get().subject().subjectType()).isEqualTo(ActorType.ADMIN_ACCOUNT);
        assertThat(r.get().subject().subjectId()).isEqualTo(ACCOUNT_ID);
        assertThat(r.get().subject().role()).isEqualTo(AdminRole.SUPER_ADMIN);
        verify(sessions).save(any(AdminSession.class));
    }

    @Test
    void wrongPasswordIsRejectedWithoutIssuingASession() {
        when(accounts.findByUsername("root")).thenReturn(Optional.of(account(AdminRole.OPERATOR, "pw")));

        assertThat(service.login("root", "wrong", TTL)).isEmpty();
        verify(sessions, never()).save(any());
    }

    @Test
    void unknownUsernameIsRejected() {
        when(accounts.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThat(service.login("ghost", "pw", TTL)).isEmpty();
    }

    @Test
    void disabledAccountCannotLogIn() {
        AdminAccount a = account(AdminRole.OPERATOR, "pw");
        a.disable(NOW);
        when(accounts.findByUsername("root")).thenReturn(Optional.of(a));

        assertThat(service.login("root", "pw", TTL)).isEmpty();
    }

    @Test
    void lockedAccountCannotLogIn() {
        AdminAccount a = account(AdminRole.OPERATOR, "pw");
        a.recordFailedAttempt(1, Duration.ofMinutes(15), NOW);   // 阈值 1 → 立即锁定
        when(accounts.findByUsername("root")).thenReturn(Optional.of(a));

        assertThat(service.login("root", "pw", TTL)).isEmpty();
    }

    @Test
    void repeatedFailuresLockTheAccount() {
        AdminAccount a = account(AdminRole.OPERATOR, "pw");
        when(accounts.findByUsername("root")).thenReturn(Optional.of(a));
        when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));

        for (int i = 0; i < 5; i++) {   // 阈值 5（见构造）
            service.login("root", "wrong", TTL);
        }

        assertThat(a.isLocked(NOW)).as("连续失败达阈值即锁定").isTrue();
    }

    @Test
    void authenticateReturnsSubjectForUsableSession() {
        String token = "tok-123";
        AdminSession s = new AdminSession(AdminAuthService.hashToken(token),
                ActorType.ADMIN_ACCOUNT, ACCOUNT_ID, NOW, NOW.plus(TTL));
        when(sessions.findByTokenHash(AdminAuthService.hashToken(token))).thenReturn(Optional.of(s));
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account(AdminRole.OPERATOR, "pw")));

        Optional<AdminAuthService.Authenticated> subject = service.authenticate(token);

        assertThat(subject).isPresent();
        assertThat(subject.get().subjectType()).isEqualTo(ActorType.ADMIN_ACCOUNT);
        assertThat(subject.get().subjectId()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    void expiredSessionIsRejected() {
        AdminSession expired = new AdminSession(AdminAuthService.hashToken("e"),
                ActorType.ADMIN_ACCOUNT, ACCOUNT_ID, NOW.minus(TTL), NOW.minusSeconds(1));
        when(sessions.findByTokenHash(AdminAuthService.hashToken("e"))).thenReturn(Optional.of(expired));

        assertThat(service.authenticate("e")).isEmpty();
    }

    @Test
    void disabledAccountInvalidatesExistingSessionImmediately() {
        // 会话本身有效，但账号已停用 → 校验必须即时拒绝（服务端会话的价值所在）
        String token = "tok";
        AdminSession s = new AdminSession(AdminAuthService.hashToken(token),
                ActorType.ADMIN_ACCOUNT, ACCOUNT_ID, NOW, NOW.plus(TTL));
        when(sessions.findByTokenHash(AdminAuthService.hashToken(token))).thenReturn(Optional.of(s));
        AdminAccount disabled = account(AdminRole.OPERATOR, "pw");
        disabled.disable(NOW);
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(disabled));

        assertThat(service.authenticate(token)).isEmpty();
    }

    @Test
    void logoutRevokesTheSession() {
        String token = "tok";
        AdminSession s = new AdminSession(AdminAuthService.hashToken(token),
                ActorType.ADMIN_ACCOUNT, ACCOUNT_ID, NOW, NOW.plus(TTL));
        when(sessions.findByTokenHash(AdminAuthService.hashToken(token))).thenReturn(Optional.of(s));
        when(sessions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.logout(token);

        assertThat(s.getRevokedAt()).isNotNull();
        verify(sessions).save(s);
    }
}

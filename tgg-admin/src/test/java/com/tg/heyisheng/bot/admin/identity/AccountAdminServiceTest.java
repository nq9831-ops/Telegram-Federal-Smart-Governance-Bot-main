package com.tg.heyisheng.bot.admin.identity;

import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.platform.PlatformGrantSource;
import com.tg.heyisheng.bot.core.platform.PlatformPermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账号管理（模块十一 · 权限模型）。
 *
 * <p>守的是安全不变量：① 建的永远是操作员（不可建超管）；② 超管账号不可停用、不可改能力；
 * ③ 停用/改密即吊销会话。
 */
class AccountAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    private final AdminAccountRepository accounts = mock(AdminAccountRepository.class);
    private final PlatformGrantSource grants = mock(PlatformGrantSource.class);
    private final AdminAuthService auth = mock(AdminAuthService.class);
    private final AccountAdminService service =
            new AccountAdminService(accounts, grants, auth, Clock.fixed(NOW, ZoneOffset.UTC));

    private static AdminAccount account(Long id, AdminRole role) {
        AdminAccount a = new AdminAccount("u" + id, PasswordHasher.hash("pw"), role, NOW);
        set(a, "id", id);
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
    void createOperatorAlwaysCreatesOperatorNotSuperAdmin() {
        when(accounts.existsByUsername("op")).thenReturn(false);
        when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AdminAccount created = service.createOperator("op", "secret");

        assertThat(created.getRole()).isEqualTo(AdminRole.OPERATOR);
        assertThat(created.isSuperAdmin()).isFalse();
    }

    @Test
    void duplicateUsernameRejected() {
        when(accounts.existsByUsername("op")).thenReturn(true);

        assertThatThrownBy(() -> service.createOperator("op", "pw"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(accounts, never()).save(any());
    }

    @Test
    void cannotDisableSuperAdmin() {
        when(accounts.findById(1L)).thenReturn(Optional.of(account(1L, AdminRole.SUPER_ADMIN)));

        assertThatThrownBy(() -> service.setStatus(1L, AdminStatus.DISABLED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disablingOperatorRevokesItsSessions() {
        when(accounts.findById(2L)).thenReturn(Optional.of(account(2L, AdminRole.OPERATOR)));
        when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.setStatus(2L, AdminStatus.DISABLED);

        verify(auth).revokeAllFor(ActorType.ADMIN_ACCOUNT, 2L);
    }

    @Test
    void cannotChangeSuperAdminPermissions() {
        when(accounts.findById(1L)).thenReturn(Optional.of(account(1L, AdminRole.SUPER_ADMIN)));

        assertThatThrownBy(() -> service.setPermissions(1L, Set.of(PlatformPermission.CONFIG_WRITE), 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setPermissionsReplacesExistingOnes() {
        when(accounts.findById(2L)).thenReturn(Optional.of(account(2L, AdminRole.OPERATOR)));
        when(grants.permissionsOf(ActorType.ADMIN_ACCOUNT, 2L))
                .thenReturn(Set.of(PlatformPermission.REVIEW_DECIDE));

        service.setPermissions(2L, Set.of(PlatformPermission.CONFIG_WRITE), 1L);

        verify(grants).revoke(ActorType.ADMIN_ACCOUNT, 2L, PlatformPermission.REVIEW_DECIDE);
        verify(grants).grant(ActorType.ADMIN_ACCOUNT, 2L, PlatformPermission.CONFIG_WRITE, 1L);
    }

    @Test
    void resetPasswordRevokesSessions() {
        when(accounts.findById(2L)).thenReturn(Optional.of(account(2L, AdminRole.OPERATOR)));
        when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resetPassword(2L, "newpw");

        verify(auth).revokeAllFor(ActorType.ADMIN_ACCOUNT, 2L);
    }
}

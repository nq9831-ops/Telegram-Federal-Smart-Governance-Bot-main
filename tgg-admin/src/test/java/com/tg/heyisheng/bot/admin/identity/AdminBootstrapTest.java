package com.tg.heyisheng.bot.admin.identity;

import com.tg.heyisheng.bot.admin.AdminProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 超管引导。
 *
 * <p>守的要点：<b>超管只能由环境变量引导，且幂等</b>——重启不重复创建、不覆盖已改的密码。
 * 未配置时不创建任何账号（此时系统无超管，是刻意的 fail-visible，见启动 WARN）。
 */
class AdminBootstrapTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    private final AdminAccountRepository accounts = mock(AdminAccountRepository.class);
    private final AdminProperties props = new AdminProperties();
    private final AdminBootstrap bootstrap =
            new AdminBootstrap(accounts, props, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void doesNothingWhenNotConfigured() {
        bootstrap.bootstrapSuperAdmin();

        verify(accounts, never()).save(any());
    }

    @Test
    void createsSuperAdminWhenAbsent() {
        props.setSuperUser("root");
        props.setSuperPasswordHash(PasswordHasher.hash("pw"));
        when(accounts.existsByUsername("root")).thenReturn(false);
        when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));

        bootstrap.bootstrapSuperAdmin();

        ArgumentCaptor<AdminAccount> captor = ArgumentCaptor.forClass(AdminAccount.class);
        verify(accounts).save(captor.capture());
        assertThat(captor.getValue().isSuperAdmin()).isTrue();
        assertThat(captor.getValue().getUsername()).isEqualTo("root");
    }

    @Test
    void skipsWhenAlreadyExists() {
        props.setSuperUser("root");
        props.setSuperPasswordHash(PasswordHasher.hash("pw"));
        when(accounts.existsByUsername("root")).thenReturn(true);

        bootstrap.bootstrapSuperAdmin();

        verify(accounts, never()).save(any());
    }
}

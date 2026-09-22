package com.tg.heyisheng.bot.core.platform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 联邦管理员判定的单测：全局白名单语义。（随判定器上移 core.platform，2026-09-22） */
class FederationAdminGuardTest {

    @Test
    void admitsConfiguredAdmin() {
        FederationAdminGuard guard = new FederationAdminGuard(List.of(42L, 7L));

        assertThat(guard.isAdmin(42L)).isTrue();
        assertThat(guard.isAdmin(7L)).isTrue();
        assertThat(guard.size()).isEqualTo(2);
    }

    @Test
    void rejectsEveryoneElse() {
        FederationAdminGuard guard = new FederationAdminGuard(List.of(42L));

        assertThat(guard.isAdmin(43L)).isFalse();
        assertThat(guard.isAdmin(null)).as("null 用户不得被判为管理员").isFalse();
    }

    @Test
    void emptyConfigAdmitsNobody() {
        assertThat(new FederationAdminGuard(List.of()).isAdmin(42L)).isFalse();
        assertThat(new FederationAdminGuard((Iterable<Long>) null).size()).isZero();
    }

    /** 热生效：经配置服务读取时，改名单无需重启。 */
    @Test
    void configBackedGuardReflectsChangesWithoutRestart() {
        com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService cfg =
                new com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService(
                        org.mockito.Mockito.mock(com.tg.heyisheng.bot.core.config.dynamic.ConfigOverrideRepository.class),
                        new org.springframework.mock.env.MockEnvironment());
        FederationAdminGuard guard = new FederationAdminGuard(cfg);
        assertThat(guard.isAdmin(42L)).isFalse();

        cfg.set(FederationAdminGuard.KEY, "42", null);

        assertThat(guard.isAdmin(42L)).as("改联邦管理员名单后无需重启即生效").isTrue();
    }
}

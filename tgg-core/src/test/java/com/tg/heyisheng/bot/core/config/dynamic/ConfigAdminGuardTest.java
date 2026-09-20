package com.tg.heyisheng.bot.core.config.dynamic;

import com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 配置写权限判定测试。
 *
 * <p>三条语义各一测：显式名单优先、空则回落复核人、两者皆空则**无人可写**（fail-closed）。
 * 最后一条最要紧——「没人能写」必须是显式的，而不是被静默当成「谁都能写」。
 */
class ConfigAdminGuardTest {

    private static RuntimeConfigService config(String configAdmins) {
        MockEnvironment environment = new MockEnvironment();
        if (configAdmins != null) {
            environment.withProperty(ConfigAdminGuard.CONFIG_ADMINS_KEY, configAdmins);
        }
        return new RuntimeConfigService(mock(ConfigOverrideRepository.class), environment,
                Clock.systemUTC());
    }

    private static ModerationReviewGuard reviewers(String spec) {
        return new ModerationReviewGuard(spec);
    }

    @Test
    void explicitWhitelistWins() {
        ConfigAdminGuard guard = new ConfigAdminGuard(config("42"), reviewers("777"));
        assertThat(guard.isConfigAdmin(42L)).isTrue();
        assertThat(guard.isConfigAdmin(777L)).as("显式名单非空时不再回落").isFalse();
    }

    @Test
    void fallsBackToReviewersWhenExplicitEmpty() {
        ConfigAdminGuard guard = new ConfigAdminGuard(config(null), reviewers("777,888"));
        assertThat(guard.isConfigAdmin(777L)).isTrue();
        assertThat(guard.isConfigAdmin(42L)).isFalse();
    }

    @Test
    void nobodyCanWriteWhenBothEmpty() {
        ConfigAdminGuard guard = new ConfigAdminGuard(config(null), reviewers(""));
        assertThat(guard.configAdmins()).isEmpty();
        assertThat(guard.isConfigAdmin(777L)).as("无人有权——不得 fail-open").isFalse();
    }

    @Test
    void nullOperatorIsRejected() {
        ConfigAdminGuard guard = new ConfigAdminGuard(config("42"), reviewers(""));
        assertThat(guard.isConfigAdmin(null)).isFalse();
    }

    /** 写权限名单是热的：改覆盖后**不重启**即生效。 */
    @Test
    void whitelistChangeTakesEffectWithoutRestart() {
        RuntimeConfigService cfg = config(null);
        ConfigAdminGuard guard = new ConfigAdminGuard(cfg, reviewers("777"));
        assertThat(guard.isConfigAdmin(42L)).isFalse();

        cfg.set(ConfigAdminGuard.CONFIG_ADMINS_KEY, "42", null);

        assertThat(guard.isConfigAdmin(42L)).isTrue();
        assertThat(guard.isConfigAdmin(777L)).as("显式名单接管后，复核人不再自动有权").isFalse();
    }
}

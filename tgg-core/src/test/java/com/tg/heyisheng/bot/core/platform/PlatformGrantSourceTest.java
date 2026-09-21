package com.tg.heyisheng.bot.core.platform;

import com.tg.heyisheng.bot.core.audit.ActorType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台能力账本。
 *
 * <p>守的两条：① 判定按 (类型, id)——同 id 的两类主体互不影响；② <b>GRANT_MANAGE 不可授予</b>
 * （它是「分配权限」的权限，只属超管，授予出去等于开出提权路）。
 */
class PlatformGrantSourceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    private final PlatformGrantRepository repository = mock(PlatformGrantRepository.class);
    private final PlatformGrantSource source =
            new PlatformGrantSource(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void hasPermissionIsScopedByBothTypeAndId() {
        when(repository.findBySubjectTypeAndSubjectIdAndPermission(
                ActorType.ADMIN_ACCOUNT, 42L, PlatformPermission.CONFIG_WRITE))
                .thenReturn(Optional.of(new PlatformGrant(
                        ActorType.ADMIN_ACCOUNT, 42L, PlatformPermission.CONFIG_WRITE, 1L, NOW)));

        assertThat(source.hasPermission(ActorType.ADMIN_ACCOUNT, 42L, PlatformPermission.CONFIG_WRITE))
                .isTrue();
        // 同 id 的 TG 用户不应因账号被授权而获得该能力
        assertThat(source.hasPermission(ActorType.TG_USER, 42L, PlatformPermission.CONFIG_WRITE))
                .isFalse();
    }

    @Test
    void nullInputsAreDenied() {
        assertThat(source.hasPermission(null, 1L, PlatformPermission.CONFIG_WRITE)).isFalse();
        assertThat(source.hasPermission(ActorType.ADMIN_ACCOUNT, null, PlatformPermission.CONFIG_WRITE)).isFalse();
        assertThat(source.hasPermission(ActorType.ADMIN_ACCOUNT, 1L, null)).isFalse();
    }

    @Test
    void grantManageIsRefused() {
        assertThatThrownBy(() -> source.grant(ActorType.ADMIN_ACCOUNT, 2L,
                PlatformPermission.GRANT_MANAGE, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void grantIsIdempotent() {
        when(repository.findBySubjectTypeAndSubjectIdAndPermission(
                ActorType.ADMIN_ACCOUNT, 2L, PlatformPermission.REVIEW_DECIDE))
                .thenReturn(Optional.of(new PlatformGrant(
                        ActorType.ADMIN_ACCOUNT, 2L, PlatformPermission.REVIEW_DECIDE, 1L, NOW)));

        source.grant(ActorType.ADMIN_ACCOUNT, 2L, PlatformPermission.REVIEW_DECIDE, 1L);

        verify(repository, never()).save(any());
    }
}

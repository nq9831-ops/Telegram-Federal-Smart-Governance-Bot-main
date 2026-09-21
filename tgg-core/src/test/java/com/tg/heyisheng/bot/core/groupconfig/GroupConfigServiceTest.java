package com.tg.heyisheng.bot.core.groupconfig;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 群组配置服务单测（不依赖数据库）。
 *
 * <p><b>本类守的核心不变量</b>：读配置失败时回退<b>上次已知配置</b>，而不是默认配置——
 * 否则被 {@code /disable} 的群会在 DB 抖动期间「恢复响应」，
 * 把群主的关闭意愿做成了反效果（见 {@code KNOWN-ISSUES} 定向审计段 #1）。
 * 真实数据库行为由 {@code GroupConfigPersistenceIT} 覆盖。
 */
class GroupConfigServiceTest {

    private static final long CHAT = -100L;

    private final GroupConfigRepository repository = mock(GroupConfigRepository.class);

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void unregisteredGroupDefaultsToEnabled() {
        when(repository.findById(CHAT)).thenReturn(Optional.empty());
        GroupConfigService service = new GroupConfigService(repository, fixedClock());

        GroupConfigView view = service.findOrDefault(CHAT);

        assertThat(view.enabled()).as("未登记的群默认启用，避免机器人进群后什么都不做").isTrue();
        assertThat(view.chatId()).isEqualTo(CHAT);
    }

    @Test
    void nullChatIdReturnsDefaultWithoutQueryingRepository() {
        GroupConfigService service = new GroupConfigService(repository, fixedClock());

        assertThat(service.findOrDefault(null).enabled()).isTrue();
        verify(repository, times(0)).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void findOrDefaultHitsCacheWithinTtl() {
        GroupConfig config = new GroupConfig(CHAT, "测试群");
        when(repository.findById(CHAT)).thenReturn(Optional.of(config));
        GroupConfigService service = new GroupConfigService(repository, fixedClock());

        service.findOrDefault(CHAT);
        service.findOrDefault(CHAT);
        service.findOrDefault(CHAT);

        verify(repository, times(1)).findById(CHAT);
    }

    /**
     * 核心不变量：缓存过期后查库失败，必须回退「上次已知配置」——
     * 被 {@code /disable} 的群要保持关闭，而不是被默认配置（enabled=true）复活。
     */
    @Test
    void databaseFailureKeepsDisabledGroupClosed() {
        GroupConfig disabled = new GroupConfig(CHAT, "被关闭的群");
        disabled.setEnabled(false);
        when(repository.findById(CHAT)).thenReturn(Optional.of(disabled));
        MutableClock clock = new MutableClock(Instant.parse("2026-09-20T00:00:00Z"));
        GroupConfigService service = new GroupConfigService(repository, clock);

        assertThat(service.findOrDefault(CHAT).enabled()).as("预热：读到关闭态").isFalse();

        clock.advance(GroupConfigService.CACHE_TTL.plusSeconds(1)); // 缓存过期，条目仍作 last-known
        when(repository.findById(CHAT)).thenThrow(new RuntimeException("db down"));

        assertThat(service.findOrDefault(CHAT).enabled())
                .as("DB 抖动不得让被 /disable 的群恢复响应")
                .isFalse();
    }

    @Test
    void databaseFailureWithNoCacheFallsBackToDefaultEnabled() {
        when(repository.findById(CHAT)).thenThrow(new RuntimeException("db down"));
        GroupConfigService service = new GroupConfigService(repository, fixedClock());

        assertThat(service.findOrDefault(CHAT).enabled())
                .as("本进程从未读到过该群配置时，只能按默认（启用）放行并记 ERROR")
                .isTrue();
    }

    @Test
    void setEnabledInvalidatesCacheSoSwitchTakesEffectImmediately() {
        GroupConfig enabled = new GroupConfig(CHAT, "测试群"); // 默认 enabled=true
        when(repository.findById(CHAT)).thenReturn(Optional.of(enabled));
        GroupConfigService service = new GroupConfigService(repository, fixedClock());

        service.findOrDefault(CHAT);            // 预热缓存（enabled=true）

        // 原子 upsert 落库后，该行应呈关闭态
        GroupConfig disabled = new GroupConfig(CHAT, "测试群");
        disabled.setEnabled(false);
        when(repository.findById(CHAT)).thenReturn(Optional.of(disabled));

        service.setEnabled(CHAT, false);        // 原子 upsert + 失效缓存

        assertThat(service.findOrDefault(CHAT).enabled())
                .as("关闭后必须立刻生效，不能等 TTL")
                .isFalse();
        verify(repository).upsertEnabled(org.mockito.ArgumentMatchers.eq(CHAT),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.any());
    }

    /** 可控时钟：让「缓存过期后的 last-known 回退」可被直接验证（固定时钟无法推进时间）。 */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration delta) {
            now = now.plus(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

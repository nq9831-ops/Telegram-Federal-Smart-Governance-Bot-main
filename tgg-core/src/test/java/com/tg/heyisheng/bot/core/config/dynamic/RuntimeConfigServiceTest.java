package com.tg.heyisheng.bot.core.config.dynamic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RuntimeConfigService} 的解析、校验与打码测试。
 *
 * <p>不启动 Spring：用一个内存假仓库 + {@link MockEnvironment} 直接驱动——
 * 校验逻辑与解析顺序是本类最该被钉住的部分，且都不依赖上下文。
 */
class RuntimeConfigServiceTest {

    /** 内存假仓库。 */
    private static final class FakeRepo implements ConfigOverrideRepository {
        private final Map<String, ConfigOverride> rows = new LinkedHashMap<>();

        @Override
        public List<ConfigOverride> findAll() {
            return new ArrayList<>(rows.values());
        }

        @Override
        public Optional<ConfigOverride> findById(String configKey) {
            return Optional.ofNullable(rows.get(configKey));
        }

        @Override
        public ConfigOverride save(ConfigOverride override) {
            rows.put(override.getConfigKey(), override);
            return override;
        }

        @Override
        public void deleteById(String configKey) {
            rows.remove(configKey);
        }
    }

    private FakeRepo repo;
    private MockEnvironment environment;
    private RuntimeConfigService service;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        environment = new MockEnvironment();
        service = new RuntimeConfigService(repo, environment,
                Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC));
    }

    // ───────────────────────── 解析顺序 ─────────────────────────

    @Test
    void fallsBackToDefaultWhenUnset() {
        assertThat(service.resolve("tgg.admin.overdue-remind-hours")).contains("24");
        assertThat(service.sourceOf("tgg.admin.overdue-remind-hours")).isEqualTo("default");
    }

    @Test
    void environmentWinsOverDefault() {
        environment.withProperty("tgg.admin.overdue-remind-hours", "48");
        assertThat(service.getInt("tgg.admin.overdue-remind-hours", 24)).isEqualTo(48);
        assertThat(service.sourceOf("tgg.admin.overdue-remind-hours")).isEqualTo("environment");
    }

    @Test
    void overrideWinsOverEnvironment() {
        environment.withProperty("tgg.admin.overdue-remind-hours", "48");
        service.set("tgg.admin.overdue-remind-hours", "6", 42L);

        assertThat(service.getInt("tgg.admin.overdue-remind-hours", 24)).isEqualTo(6);
        assertThat(service.sourceOf("tgg.admin.overdue-remind-hours")).isEqualTo("override");
    }

    /** 热参数：写入后**无需重载**即读到新值（这是「热生效」的判据）。 */
    @Test
    void hotParamReflectsNewValueImmediately() {
        assertThat(service.getHours("tgg.admin.overdue-escalate-hours", 72).toHours()).isEqualTo(72);
        service.set("tgg.admin.overdue-escalate-hours", "12", 42L);
        assertThat(service.getHours("tgg.admin.overdue-escalate-hours", 72).toHours()).isEqualTo(12);
    }

    @Test
    void clearRevertsToEnvironmentOrDefault() {
        service.set("tgg.admin.overdue-remind-hours", "6", 42L);
        assertThat(service.hasOverride("tgg.admin.overdue-remind-hours")).isTrue();

        service.clear("tgg.admin.overdue-remind-hours", 42L);

        assertThat(service.hasOverride("tgg.admin.overdue-remind-hours")).isFalse();
        assertThat(service.getInt("tgg.admin.overdue-remind-hours", 24)).isEqualTo(24);
    }

    // ───────────────────────── 写入校验 ─────────────────────────

    @Test
    void rejectsUnknownKey() {
        assertThatThrownBy(() -> service.set("tgg.nope", "1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知配置键");
    }

    /** 密钥/引导态键经 Web 写入必须被拒——即使调用方绕过前端直接打接口。 */
    @Test
    void rejectsNonWritableKeys() {
        assertThatThrownBy(() -> service.set("tgg.webhook.bot-token", "leak", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不可经 Web 改写");
        assertThatThrownBy(() -> service.set("tgg.webhook.secret", "leak", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.rows).isEmpty();
    }

    @Test
    void rejectsInvalidBoolean() {
        assertThatThrownBy(() -> service.set("tgg.listing.enabled", "yes", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("true / false");
    }

    @Test
    void rejectsOutOfRangeHours() {
        assertThatThrownBy(() -> service.set("tgg.admin.overdue-remind-hours", "-5", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.set("tgg.admin.overdue-remind-hours", "0", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("下界");
        assertThatThrownBy(() -> service.set("tgg.admin.overdue-remind-hours", "abc", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("整数");
    }

    @Test
    void rejectsInvalidCsvIds() {
        assertThatThrownBy(() -> service.set("tgg.moderation.reviewers", "42,abc", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abc");
    }

    /** 前置条件：启用装配开关但依赖未配 → 拒绝（否则下次启动 fail-fast）。 */
    @Test
    void rejectsEnablingAssemblySwitchWithoutPrerequisite() {
        assertThatThrownBy(() -> service.set("tgg.credit.enabled", "true", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tgg.credit.private-key");
        assertThat(repo.rows).isEmpty();
    }

    @Test
    void allowsEnablingAssemblySwitchWhenPrerequisitePresent() {
        environment.withProperty("tgg.credit.private-key", "BASE64KEY");
        service.set("tgg.credit.enabled", "true", 42L);
        assertThat(service.getBoolean("tgg.credit.enabled", false)).isTrue();
    }

    /** 无前置条件的开关可直接开启。 */
    @Test
    void allowsEnablingPlainSwitch() {
        service.set("tgg.listing.enabled", "true", 42L);
        assertThat(service.getBoolean("tgg.listing.enabled", false)).isTrue();
    }

    @Test
    void setPersistsToRepository() {
        service.set("tgg.admin.overdue-remind-hours", "6", 42L);
        assertThat(repo.rows).containsKey("tgg.admin.overdue-remind-hours");
        assertThat(repo.rows.get("tgg.admin.overdue-remind-hours").getConfigValue()).isEqualTo("6");
        assertThat(repo.rows.get("tgg.admin.overdue-remind-hours").getUpdatedBy()).isEqualTo(42L);
    }

    // ───────────────────────── 总览打码 ─────────────────────────

    @Test
    void snapshotNeverLeaksSecretValues() {
        environment.withProperty("tgg.webhook.bot-token", "123456:SUPER-SECRET-TOKEN");
        environment.withProperty("tgg.admin.api-token", "SUPER-SECRET-ADMIN-TOKEN");

        List<RuntimeConfigService.Resolved> view = service.snapshot();

        assertThat(view).allSatisfy(item -> {
            assertThat(item.effectiveValue()).doesNotContain("SUPER-SECRET");
        });
        RuntimeConfigService.Resolved token = view.stream()
                .filter(v -> v.key().equals("tgg.webhook.bot-token")).findFirst().orElseThrow();
        assertThat(token.secret()).isTrue();
        assertThat(token.effectiveValue()).isEqualTo("***");
    }

    @Test
    void snapshotShowsNotSetForMissingSecret() {
        RuntimeConfigService.Resolved token = service.snapshot().stream()
                .filter(v -> v.key().equals("tgg.webhook.bot-token")).findFirst().orElseThrow();
        assertThat(token.effectiveValue()).isEqualTo("未设置");
    }

    @Test
    void snapshotExposesNonSecretValue() {
        RuntimeConfigService.Resolved hours = service.snapshot().stream()
                .filter(v -> v.key().equals("tgg.admin.overdue-remind-hours")).findFirst().orElseThrow();
        assertThat(hours.effectiveValue()).isEqualTo("24");
        assertThat(hours.restartRequired()).isFalse();
    }
}

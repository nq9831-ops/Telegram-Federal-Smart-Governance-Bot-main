package com.tg.heyisheng.bot.core.config.dynamic;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置键目录的<b>不变量</b>测试。
 *
 * <p>它守的是「目录本身自洽」——键唯一、分类与可写性一致、装配开关都标了重启、
 * 每条都有说明与合理上下界。这些错了不会抛异常，只会让总览显示错、写入放行不该放的键、
 * 或前端拿到误导性描述——典型的静默失效，故用测试钉住。
 */
class ConfigCatalogTest {

    @Test
    void keysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (ConfigKey key : ConfigCatalog.keys()) {
            assertThat(seen.add(key.key())).as("配置键重复：%s", key.key()).isTrue();
        }
    }

    /** 密钥 / 引导态**永不**可写——这是「统一管理」里最不能妥协的一条。 */
    @Test
    void secretAndBootstrapKeysAreNeverWritable() {
        for (ConfigKey key : ConfigCatalog.keys()) {
            if (key.category() == ConfigCategory.SECRET || key.category() == ConfigCategory.BOOTSTRAP) {
                assertThat(key.writable())
                        .as("%s 属 %s，绝不可经 Web 改写", key.key(), key.category())
                        .isFalse();
            }
        }
    }

    /** 密钥键必须标 secret（否则总览会回显明文）；非密钥键反之应能正常展示。 */
    @Test
    void everyCategorySecretKeyIsMarkedSecret() {
        for (ConfigKey key : ConfigCatalog.keys()) {
            if (key.category() == ConfigCategory.SECRET) {
                assertThat(key.secret()).as("%s 是密钥，必须标记 secret", key.key()).isTrue();
            }
        }
    }

    /** 装配开关（@ConditionalOnProperty）在启动期求值——必须诚实标注「重启生效」。 */
    @Test
    void assemblySwitchesRequireRestart() {
        for (ConfigKey key : ConfigCatalog.keys()) {
            if (key.category() == ConfigCategory.ASSEMBLY) {
                assertThat(key.restartRequired())
                        .as("%s 是装配开关，必须标注重启生效", key.key())
                        .isTrue();
            }
        }
    }

    @Test
    void everyKeyHasDescription() {
        for (ConfigKey key : ConfigCatalog.keys()) {
            assertThat(key.description()).as("%s 缺说明（总览会显示空白）", key.key()).isNotBlank();
        }
    }

    /** 前置条件键必须真实存在于目录中，否则校验会「查了个不存在的键」。 */
    @Test
    void prerequisiteReferencesExistingKey() {
        for (ConfigKey key : ConfigCatalog.keys()) {
            if (key.requiresKeyWhenEnabled() != null) {
                assertThat(ConfigCatalog.find(key.requiresKeyWhenEnabled()))
                        .as("%s 的前置键 %s 不在目录中", key.key(), key.requiresKeyWhenEnabled())
                        .isPresent();
            }
        }
    }

    @Test
    void boundsAreSane() {
        for (ConfigKey key : ConfigCatalog.keys()) {
            if (key.min() != null && key.max() != null) {
                assertThat(key.min()).as("%s 的 min 不应大于 max", key.key())
                        .isLessThanOrEqualTo(key.max());
            }
        }
    }

    /** 首批热参数：可写且**不**需重启（其消费方已改造为调用期读取）。 */
    @Test
    void hotAdminOverdueParamsAreWritableAndHot() {
        for (String hot : new String[]{
                "tgg.admin.overdue-remind-hours", "tgg.admin.overdue-escalate-hours"}) {
            ConfigKey key = ConfigCatalog.find(hot).orElseThrow();
            assertThat(key.writable()).as("%s 应可写", hot).isTrue();
            assertThat(key.restartRequired()).as("%s 应标注为热生效", hot).isFalse();
        }
    }

    /**
     * 不变量：可写的**运行期参数**必须显式声明它是「热生效」还是「需重启」。
     *
     * <p>本测试的前一版要求「全部必须热」——被新增的 cron 键证伪：cron 可写，但
     * {@code @Scheduled(cron = "${...}")} 在装配期就把表达式固定了，改了确实要重启。
     * 所以真正该守的不是「都热」，而是**不许含糊**：说明里必须写明其中一种，
     * 否则运维改了配置却无从判断要不要重启。
     */
    @Test
    void everyWritableRuntimeKeyDeclaresHotOrRestart() {
        for (ConfigKey key : ConfigCatalog.keys()) {
            if (key.category() == ConfigCategory.RUNTIME && key.writable()) {
                assertThat(key.description())
                        .as("%s 可写却是运行期参数，说明里必须写明「热生效」或「需重启」", key.key())
                        .matches("(?s).*(热生效|需重启).*");
            }
        }
    }

    /** 保留开关不门控装配（RetentionConfiguration 的 bean 无条件创建），故属运行期参数而非装配开关。 */
    @Test
    void retentionEnabledIsRuntimeNotAssembly() {
        assertThat(ConfigCatalog.find("tgg.retention.enabled").orElseThrow().category())
                .isEqualTo(ConfigCategory.RUNTIME);
    }
}

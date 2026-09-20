package com.tg.heyisheng.bot.core.config.dynamic;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注入器的「失败归因」测试。
 *
 * <p>为什么这点值得单测：两种失败在**应用行为上完全相同**（都是跳过注入），但运维含义相反——
 * 「表不存在」是首次启动的正常情况（静默即可），「库读失败」意味着**本次启动忽略了库里的覆盖**、
 * 开关按旧值装配。若归错类，第二种会以「一切正常」的样子消失，运维只会看到「我改了并重启了，没生效」。
 */
class ConfigOverrideEnvironmentPostProcessorTest {

    @Test
    void tableMissingIsRecognisedByErrorCode() {
        assertThat(ConfigOverrideEnvironmentPostProcessor.isTableMissing(
                new SQLException("Table 'tgg.config_override' doesn't exist", "42S02", 1146)))
                .isTrue();
    }

    @Test
    void tableMissingIsRecognisedByMessageWhenCodesAbsent() {
        assertThat(ConfigOverrideEnvironmentPostProcessor.isTableMissing(
                new SQLException("Table 'x.config_override' doesn't exist")))
                .as("驱动未给 SQLState / errorCode 时靠消息文本兜底").isTrue();
        assertThat(ConfigOverrideEnvironmentPostProcessor.isTableMissing(
                new SQLException("Unknown table 'config_override'"))).isTrue();
    }

    /** ⚠️ 关键反例：「库不存在」不是「表不存在」——后者静默、前者必须 WARN。 */
    @Test
    void unknownDatabaseIsNotTreatedAsMissingTable() {
        assertThat(ConfigOverrideEnvironmentPostProcessor.isTableMissing(
                new SQLException("Unknown database 'no_such_db'", "42000", 1049)))
                .as("库名写错属真正的失败，必须走 WARN 而非静默 debug")
                .isFalse();
    }

    @Test
    void connectionFailureIsNotTreatedAsMissingTable() {
        assertThat(ConfigOverrideEnvironmentPostProcessor.isTableMissing(
                new SQLException("Communications link failure")))
                .isFalse();
    }

    @Test
    void accessDeniedIsNotTreatedAsMissingTable() {
        assertThat(ConfigOverrideEnvironmentPostProcessor.isTableMissing(
                new SQLException("Access denied for user 'tgg'@'localhost'", "28000", 1045)))
                .isFalse();
    }
}

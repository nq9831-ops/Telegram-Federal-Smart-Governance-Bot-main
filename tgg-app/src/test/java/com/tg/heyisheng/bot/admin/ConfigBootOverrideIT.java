package com.tg.heyisheng.bot.admin;

import com.tg.heyisheng.bot.core.config.dynamic.ConfigOverrideEnvironmentPostProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 装配开关「重启生效」的启动期注入测试。
 *
 * <p>验证 {@link ConfigOverrideEnvironmentPostProcessor}：从 {@code config_override} 读覆盖并注入
 * Environment——这正是「后台改开关 → 重启 → 生效」闭环里最难验的一跳。
 * 这里直接驱动该后置处理器（构造一个干净 Environment + 真实 MySQL），而不是重启整个上下文。
 */
@SpringBootTest
class ConfigBootOverrideIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.core.env.Environment runningEnvironment;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM config_override");
    }

    @Test
    void injectsOverridesFromDatabaseIntoEnvironment() {
        jdbcTemplate.update("INSERT INTO config_override(config_key, config_value, updated_at, updated_by) "
                + "VALUES (?, ?, UTC_TIMESTAMP(6), NULL)", "tgg.listing.enabled", "true");

        ConfigurableEnvironment fresh = freshEnvironmentWithDatasource();

        new ConfigOverrideEnvironmentPostProcessor().postProcessEnvironment(fresh, null);

        assertThat(fresh.getProperty("tgg.listing.enabled"))
                .as("覆盖值应被注入 Environment —— 装配开关据此在本次启动生效")
                .isEqualTo("true");
    }

    /** 表不存在（首次启动）或库不可达时必须**静默跳过**，绝不因此让应用起不来。 */
    @Test
    void unreachableDatabaseIsSkippedSilently() {
        ConfigurableEnvironment fresh = new StandardEnvironment();
        Map<String, Object> ds = new HashMap<>();
        ds.put("spring.datasource.url", "jdbc:mysql://localhost:3306/no_such_db_tgg_zzz");
        ds.put("spring.datasource.username", "root");
        ds.put("spring.datasource.password", "");
        fresh.getPropertySources().addFirst(new MapPropertySource("ds", ds));

        assertThatCode(() -> new ConfigOverrideEnvironmentPostProcessor()
                .postProcessEnvironment(fresh, null))
                .as("库不可达不得抛出——配置中心不是启动的单点")
                .doesNotThrowAnyException();
        assertThat(fresh.getProperty("tgg.listing.enabled")).isNull();
    }

    private ConfigurableEnvironment freshEnvironmentWithDatasource() {
        ConfigurableEnvironment fresh = new StandardEnvironment();
        Map<String, Object> ds = new HashMap<>();
        ds.put("spring.datasource.url", runningEnvironment.getProperty("spring.datasource.url", ""));
        ds.put("spring.datasource.username", runningEnvironment.getProperty("spring.datasource.username", ""));
        ds.put("spring.datasource.password", runningEnvironment.getProperty("spring.datasource.password", ""));
        fresh.getPropertySources().addFirst(new MapPropertySource("ds", ds));
        return fresh;
    }
}

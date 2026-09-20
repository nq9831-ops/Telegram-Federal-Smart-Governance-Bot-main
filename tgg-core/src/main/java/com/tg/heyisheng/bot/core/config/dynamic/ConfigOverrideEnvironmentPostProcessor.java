package com.tg.heyisheng.bot.core.config.dynamic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 {@code config_override} 表的覆盖值注入 {@link ConfigurableEnvironment}——**在
 * {@code @ConditionalOnProperty} 求值之前**。
 *
 * <p><b>为什么必须是 EnvironmentPostProcessor</b>：装配开关（{@code @ConditionalOnProperty}）在 bean 定义
 * 求值时读 Environment。要让「后台改开关 → 重启 → 生效」成立，覆盖值就必须早于那一刻进入 Environment；
 * {@code EnvironmentPostProcessor} 正是这个时机（早于上下文刷新，也早于 Flyway）。
 *
 * <p><b>为什么用原生 JDBC 而不是 DataSource</b>：此刻 {@code DataSource} 还没建。故按
 * {@code spring.datasource.*} 现连、查表、即关。
 *
 * <p><b>失败一律静默跳过</b>：表不存在（首次启动，Flyway 未跑）或库不可达——都**不能**
 * 因此让应用起不来。配置中心是增强，不是启动的单点。
 *
 * <p>注册见 {@code tgg-core/src/main/resources/META-INF/spring.factories}。
 */
public class ConfigOverrideEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ConfigOverrideEnvironmentPostProcessor.class);

    /** 注入的 PropertySource 名（排在最高优先级，压过 yml 与环境变量）。 */
    static final String SOURCE_NAME = "configOverride";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || url.isBlank()) {
            return;
        }
        String user = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");

        Map<String, Object> overrides = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT config_key, config_value FROM config_override")) {
            while (rows.next()) {
                overrides.put(rows.getString(1), rows.getString(2));
            }
        } catch (SQLException ex) {
            // 首次启动（表未建）或库不可达：静默跳过，绝不因此让应用起不来
            log.debug("配置覆盖注入跳过（表不存在或库不可达）：{}", ex.getMessage());
            return;
        }

        if (overrides.isEmpty()) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, overrides));
        log.info("已从 config_override 注入 {} 项配置覆盖（装配开关将在本次启动按覆盖值生效）", overrides.size());
    }
}

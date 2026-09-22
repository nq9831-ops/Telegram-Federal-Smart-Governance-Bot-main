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
 * <p><b>失败归因（GUARD-2）</b>：读取失败分两类，日志级别**必须不同**——
 * 「表不存在」（首次启动，Flyway 未跑）静默跳过；**其他** SQLException（库不可达 / 权限不足 / 驱动异常）
 * 会让本次启动**忽略全部覆盖**、按 yml/环境变量默认值装配，这在运维侧表现为
 * 「我改了开关也重启了，怎么没生效」，故必须以**无法被忽略的 ERROR 警告块**醒目声明——绝不静默 fail-open。
 *
 * <p><b>为什么这里不直接拒绝启动</b>：项目把配置中心定位为**增强、不是启动的单点**（见
 * {@code db/migration/V17__init_config_override.sql} 的说明与
 * {@code ConfigBootOverrideIT#unreachableDatabaseIsSkippedSilently} 的契约）——库短暂不可达时若拒绝启动，
 * 会把「配置中心小故障」升级为「全站起不来」。故此处只把**静默**的 fail-open 变成本
 * <b>喧闹</b>的 fail-open（ERROR 警告块 + 明确后果）。若日后要升级为 fail-closed，须同步改上述 IT 契约。
 *
 * <p>注册见 {@code tgg-core/src/main/resources/META-INF/spring.factories}。
 */
public class ConfigOverrideEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ConfigOverrideEnvironmentPostProcessor.class);

    /** 注入的 PropertySource 名（排在最高优先级，压过 yml 与环境变量）。 */
    static final String SOURCE_NAME = "configOverride";

    private static final String SELECT_SQL = "SELECT config_key, config_value FROM config_override";

    /**
     * 非「表缺失」的读取失败（库不可达 / 权限不足 / 驱动异常）时打印的**醒目启动警告块**首行。
     *
     * <p>刻意提为常量：一是让运维日志里一眼可辨，二是让守门测试能按这段**稳定文本**断言
     * （见 {@code ConfigOverrideEnvironmentPostProcessorLoudnessTest}）。
     */
    static final String IGNORED_OVERRIDES_WARNING =
            "⚠️⚠️ 配置覆盖注入失败——本次启动已忽略【全部】config_override 覆盖 ⚠️⚠️";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || url.isBlank()) {
            return;
        }
        String user = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");

        Map<String, Object> overrides;
        try (Connection connection = openConnection(url, user, password)) {
            overrides = readOverrides(connection);
        } catch (SQLException ex) {
            if (isTableMissing(ex)) {
                // 首次启动的正常情况（Flyway 还没跑）——不是故障，静默即可。
                log.debug("配置覆盖注入跳过：config_override 表尚不存在（首次启动，Flyway 未跑）");
            } else {
                // 真正的读取失败（库不可达 / 权限不足 / 驱动异常）。绝不静默：本次启动将**忽略全部覆盖**、
                // 按 yml/环境变量默认值装配，运维会看到「我改了开关也重启了，怎么没生效」。故用一条
                // 无法被忽略的 ERROR 警告块把后果与目标库讲清——把静默 fail-open 变成喧闹 fail-open。
                log.error("\n{}\n"
                                + "  ── 受影响：写在配置中心的装配开关 / 阈值，本次启动**一律按 yml / 环境变量默认值**装配；\n"
                                + "  ── 运维影响：你若刚在配置中心改过开关并重启，它**不会生效**（即「改了没反应」的根因）；\n"
                                + "  ── 处理：排查数据库可达性与账号权限后重启。\n"
                                + "  ── 目标库：{}\n"
                                + "  ── 原因：{}",
                        IGNORED_OVERRIDES_WARNING, url, ex.getMessage(), ex);
            }
            return;
        }

        if (overrides.isEmpty()) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, overrides));
        log.info("已从 config_override 注入 {} 项配置覆盖（装配开关将在本次启动按覆盖值生效）", overrides.size());
    }

    /**
     * 连接获取缝：默认走 {@link DriverManager} 现连；测试覆写它以注入伪造连接（含伪造的 SQLException），
     * 从而在不依赖真实数据库的前提下驱动「失败归因」分支。行为与内联 {@code DriverManager.getConnection} 等价。
     */
    Connection openConnection(String url, String user, String password) throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * 查表读取。SQLException（含「表缺失」）向上抛出，由 {@link #postProcessEnvironment} 归因——
     * 归因逻辑与连接获取解耦，故可被单测直接驱动。
     */
    static Map<String, Object> readOverrides(Connection connection) throws SQLException {
        Map<String, Object> overrides = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(SELECT_SQL)) {
            while (rows.next()) {
                overrides.put(rows.getString(1), rows.getString(2));
            }
        }
        return overrides;
    }

    /**
     * 判定异常是否只是「表不存在」——即首次启动的正常情况，而非真正的读取失败。
     *
     * <p>依据 MySQL 的表不存在错误码（1146 / SQLState 42S02）与消息文本。
     * ⚠️ 注意区分 {@code Unknown table} 与 {@code Unknown database}：后者是**库**不存在或库名写错，
     * 属真正的失败，必须走 ERROR 警告块。
     */
    static boolean isTableMissing(SQLException ex) {
        if ("42S02".equals(ex.getSQLState()) || ex.getErrorCode() == 1146) {
            return true;
        }
        String message = ex.getMessage();
        return message != null
                && (message.contains("doesn't exist") || message.contains("Unknown table"));
    }
}

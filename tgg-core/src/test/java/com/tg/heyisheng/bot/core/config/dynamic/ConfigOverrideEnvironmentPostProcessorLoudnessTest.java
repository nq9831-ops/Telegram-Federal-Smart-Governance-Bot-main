package com.tg.heyisheng.bot.core.config.dynamic;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GUARD-2 守门：启动期 {@code config_override} 读取失败**不得静默 fail-open**。
 *
 * <p>故障面：库不可达 / 账号权限不足 / 驱动异常——这些都不是「表缺失」（首次启动的正常情况），
 * 却会让本次启动**忽略全部覆盖**、按 yml/环境变量默认值装配。运维只会看到「我改了开关又重启了，怎么没生效」。
 * 本项目把配置中心定位为「增强、不是启动单点」（见 {@link ConfigOverrideEnvironmentPostProcessor} 类注释与
 * {@code ConfigBootOverrideIT#unreachableDatabaseIsSkippedSilently}），故**不拒绝启动**，但必须以**醒目 ERROR 块**
 * 声明后果——本测试守的就是这条线：**静默 → 喧闹**。
 *
 * <p>「表缺失」反例单列：它必须**保持安静**——否则首次启动会误报，运维会把正常首启当故障。
 */
class ConfigOverrideEnvironmentPostProcessorLoudnessTest {

    private static final String DB_URL = "jdbc:mysql://db-host:3306/tgg";

    private Logger processorLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        processorLogger = (Logger) LoggerFactory.getLogger(ConfigOverrideEnvironmentPostProcessor.class);
        appender = new ListAppender<>();
        appender.start();
        processorLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        processorLogger.detachAppender(appender);
    }

    /** 伪造连接：取 Statement 即抛指定 SQLException，模拟「库不可达 / 权限不足 / 表缺失」——无需真实数据库。 */
    private static ConfigOverrideEnvironmentPostProcessor processorThrowing(SQLException ex) {
        return new ConfigOverrideEnvironmentPostProcessor() {
            @Override
            Connection openConnection(String url, String user, String password) throws SQLException {
                Connection connection = mock(Connection.class);
                when(connection.createStatement()).thenThrow(ex);
                return connection;
            }
        };
    }

    private static ConfigurableEnvironment envWithDatasource() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", DB_URL);
        environment.setProperty("spring.datasource.username", "tgg");
        environment.setProperty("spring.datasource.password", "secret");
        return environment;
    }

    private List<ILoggingEvent> errorEvents() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }

    /**
     * GUARD-2 核心：库不可达 → 本次启动忽略全部覆盖。**不得静默**——必须有一条 ERROR 警告块，
     * 点名「已忽略全部覆盖」与目标库；同时**不得**让应用起不来（配置中心不是启动单点）。
     */
    @Test
    void unreachableDatabaseIsLoudButDoesNotStopStartup() {
        ConfigurableEnvironment environment = envWithDatasource();

        assertThatCode(() -> processorThrowing(new SQLException("Communications link failure"))
                .postProcessEnvironment(environment, null))
                .as("配置中心不是启动单点：库不可达不得让应用起不来")
                .doesNotThrowAnyException();

        assertThat(errorEvents())
                .as("GUARD-2：库不可达导致忽略全部覆盖——必须 ERROR，不能静默或仅 WARN")
                .isNotEmpty();
        assertThat(errorEvents().get(0).getFormattedMessage())
                .contains("本次启动已忽略")
                .contains("config_override")
                .contains(DB_URL);
        assertThat(environment.getProperty("tgg.listing.enabled"))
                .as("读取失败 → 覆盖未注入（fail-open），但上面已断言其非静默")
                .isNull();
    }

    @Test
    void accessDeniedIsLoud() {
        assertThatCode(() -> processorThrowing(
                new SQLException("Access denied for user 'tgg'@'localhost'", "28000", 1045))
                .postProcessEnvironment(envWithDatasource(), null))
                .doesNotThrowAnyException();

        assertThat(errorEvents())
                .as("权限不足同属「非表缺失」读取失败——必须 ERROR")
                .isNotEmpty();
    }

    /** 反例：表缺失（首次启动，Flyway 未跑）不是故障——必须保持安静，不得报 ERROR。 */
    @Test
    void missingTableStaysQuietAndSkips() {
        ConfigurableEnvironment environment = envWithDatasource();

        assertThatCode(() -> processorThrowing(
                new SQLException("Table 'tgg.config_override' doesn't exist", "42S02", 1146))
                .postProcessEnvironment(environment, null))
                .doesNotThrowAnyException();

        assertThat(errorEvents())
                .as("首次启动表缺失不是故障，不得报 ERROR")
                .isEmpty();
        assertThat(environment.getProperty("tgg.listing.enabled")).isNull();
    }
}

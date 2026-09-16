package com.tg.heyisheng.bot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.telegram.telegrambots.webhook.starter.TelegramBotsSpringWebhookApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Wave 0 验证：Spring 容器可启动 + TelegramBots 10.3.0 在 Spring Boot 4.1.1 下确实可用。
 *
 * <p>后者是本波的核心未知项——依赖解析成功不等于运行时可用，因此这里
 * 不止断言「类在 classpath 上」，而是实际构造库的核心类并调用其方法。
 */
@SpringBootTest
class TggApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void springContextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    void telegramBotsWebhookApplicationIsInstantiableOnBoot4() {
        assertThatCode(() -> {
            TelegramBotsSpringWebhookApplication app = new TelegramBotsSpringWebhookApplication();
            assertThat(app.isRunning()).isTrue();
        }).doesNotThrowAnyException();
    }
}

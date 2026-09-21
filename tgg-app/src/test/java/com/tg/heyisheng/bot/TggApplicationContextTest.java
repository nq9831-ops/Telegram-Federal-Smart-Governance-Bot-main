package com.tg.heyisheng.bot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.telegram.telegrambots.webhook.starter.TelegramBotsSpringWebhookApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Wave 0 验证：Spring 容器可启动 + TelegramBots 10.3.0 在本项目的 Spring Boot 3.5.16 下确实可用。
 *
 * <p><b>为什么钉在 3.5.x</b>：Boot 4.x 改用 Jackson 3，与 TelegramBots 的 Jackson 2 注解不兼容，
 * 会让 {@code Update} 反序列化在进入控制器前就失败（见 {@code LESSONS.md} 坑 1、{@code .rivet.md}）。
 * 故本类断言的是「3.5.16 下可用」，不是「任意版本下可用」。
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
            // try-with-resources：TelegramBotsSpringWebhookApplication 实现 AutoCloseable（javap 实证），
            // 不关闭会被 JDT 报「Resource leak: 'app' is never closed」。
            try (TelegramBotsSpringWebhookApplication app = new TelegramBotsSpringWebhookApplication()) {
                assertThat(app.isRunning()).isTrue();
            }
        }).doesNotThrowAnyException();
    }
}

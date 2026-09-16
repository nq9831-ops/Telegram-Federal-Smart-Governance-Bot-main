package com.tg.heyisheng.bot.core.config;

import com.tg.heyisheng.bot.common.exception.TggConfigException;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 降级接收集的配置校验测试。
 *
 * <p>核心断言：<b>启用 failover 却没给 bot token 时必须启动失败</b>——
 * 否则会带着非法 URL 进入运行期：探测恒判不健康、registerBot 必然抛错，
 * 表现为"反复报错但看不出是配置问题"。
 */
class FailoverConfigurationTest {

    @Test
    void failsFastWhenBotTokenMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(FailoverConfiguration.class)
                .withPropertyValues("tgg.failover.enabled=true")
                .withBean(WebhookProperties.class, () -> {
                    WebhookProperties properties = new WebhookProperties();
                    properties.setSecret("test-secret");
                    // 刻意不设置 botToken
                    return properties;
                })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(TggConfigException.class)
                            .hasMessageContaining("TGG_BOT_TOKEN");
                });
    }

    /**
     * 反向断言：提供了 token 时校验不得误伤。
     * 直接驱动校验方法而不启动上下文——后者需要补齐一整串无关依赖，
     * 反而让这条断言的信号变弱。
     */
    @Test
    void validationPassesWhenBotTokenPresent() {
        WebhookProperties properties = new WebhookProperties();
        properties.setSecret("test-secret");
        properties.setBotToken("123456:TEST-TOKEN");

        assertThatCode(() -> new FailoverConfiguration(properties).requireBotToken())
                .doesNotThrowAnyException();
    }
}

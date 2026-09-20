package com.tg.heyisheng.bot.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.CommandMenuRegistrar;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.permission.InMemoryRoleSource;
import com.tg.heyisheng.bot.core.permission.RoleSource;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationListener;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 命令菜单的**装配**（{@code tgg.command-menu.enabled} 打开时真的接上线）。
 *
 * <p><b>为什么必须有它</b>：测试用的 {@code application.yml} 里
 * {@code tgg.command-menu.enabled: false}（避免真发网络请求），于是这条装配路径
 * ——包括把 {@code TelegramApiMethodExecutor#execute} 适配成 {@code Sender} 的那一行——
 * **在任何测试里都没被跑过**。本项目已多次因「开关默认关闭 → 该路径长期假绿」吃亏，
 * 判据是给每个受开关门控的装配组合至少留一条「开关打开」的测试。
 *
 * <p>本测试不触发 {@code ApplicationReadyEvent}（{@code ApplicationContextRunner} 不发布它），
 * 故即使配了 token 也不会真的发请求。
 */
class CommandMenuConfigurationTest {

    private static final String WIRING_TEST_SECRET = "wiring-test-secret";

    @BotCommand(value = "echo", description = "连通性测试", publicCommand = true)
    static class EchoHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "pong");
        }
    }

    /**
     * {@code WebhookProperties} 刻意在 {@code @PostConstruct} 里 fail-fast（缺 secret 拒绝启动），
     * 故替身必须把 secret 填上——本测试关心的是命令菜单装配，不该被那条守卫拦住。
     */
    private static WebhookProperties webhookProperties(String botToken) {
        WebhookProperties properties = new WebhookProperties();
        properties.setSecret(WIRING_TEST_SECRET);
        properties.setBotToken(botToken);
        return properties;
    }

    private ApplicationContextRunner runner(String botToken, String... properties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(CommandMenuConfiguration.class)
                .withBean(CommandRegistry.class, () -> new CommandRegistry(List.of(new EchoHandler())))
                .withBean(RoleSource.class, InMemoryRoleSource::new)
                .withBean(WebhookProperties.class, () -> webhookProperties(botToken))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(properties);
    }

    /** 默认开启（{@code matchIfMissing = true}）：注册器与「应用就绪即注册」的监听器都装配出来。 */
    @Test
    void enabledByDefaultWiresRegistrarAndReadyListener() {
        runner(null).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CommandMenuRegistrar.class);
            assertThat(context.getBeansOfType(ApplicationListener.class))
                    .as("「应用就绪后注册」必须接上线——否则命令清单永远发不出去")
                    .isNotEmpty();
        });
    }

    /** 显式关闭即不装配（也是测试 yml 里那条 false 的语义依据）。 */
    @Test
    void explicitlyDisabledProducesNoRegistrar() {
        runner(null, "tgg.command-menu.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(CommandMenuRegistrar.class));
    }

    /**
     * 配了 token 时装配照常成功——这一条专门覆盖「把返回 boolean 的执行器适配成 Sender」
     * 那一行（曾经因通道类型是 {@code Consumer} 而静默丢弃成败，且没有任何测试会红）。
     */
    @Test
    void assemblyWithTokenWiresTheRealSenderWithoutSending() {
        runner("123456:fake-token-for-wiring-test").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CommandMenuRegistrar.class);
        });
    }
}

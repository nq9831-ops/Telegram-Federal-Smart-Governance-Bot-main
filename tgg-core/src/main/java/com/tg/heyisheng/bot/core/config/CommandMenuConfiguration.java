package com.tg.heyisheng.bot.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.core.dispatch.CommandMenuRegistrar;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.failover.TelegramApiMethodExecutor;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;

import java.util.function.Consumer;

/**
 * 命令菜单的装配：把 {@code @BotCommand} 声明的命令清单注册到 Telegram。
 *
 * <p>独立成一个配置类（不塞进 {@code TggCoreConfiguration}）是刻意的：后者被多处
 * {@code ApplicationContextRunner} 装配，往里加 bean 会波及那些既有测试
 * （本项目已踩过：新增依赖 JPA 仓库的 bean 会让 RbacWiringTest 四条整体起不来）。
 *
 * <p><b>默认开启</b>（{@code tgg.command-menu.enabled} 缺省即 true）：这是「让已有功能真正可见」
 * 的修复，不是可选增强——默认关闭会让 29 个命令继续在客户端里隐形。
 */
@Configuration
@ConditionalOnProperty(prefix = "tgg.command-menu", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class CommandMenuConfiguration {

    @Bean
    public CommandMenuRegistrar commandMenuRegistrar(CommandRegistry commandRegistry,
                                                     WebhookProperties properties,
                                                     ObjectMapper objectMapper) {
        String token = properties.getBotToken();
        // 无 token 时传 null（由 registrar 负责 WARN 并跳过），而不是在这里静默不装配——
        // 「没注册」这件事必须在日志里说得出来。
        Consumer<BotApiMethod<?>> sender = (token == null || token.isBlank())
                ? null
                : new TelegramApiMethodExecutor(new OkHttpClient(), objectMapper, token)::execute;
        return new CommandMenuRegistrar(commandRegistry.mainCommands(), sender);
    }

    /**
     * 应用就绪后再注册：此时 webhook / 长轮询链路已装配完毕；
     * 注册失败只记日志，绝不阻止进程启动。
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> commandMenuRegistration(CommandMenuRegistrar registrar) {
        return event -> registrar.register();
    }
}

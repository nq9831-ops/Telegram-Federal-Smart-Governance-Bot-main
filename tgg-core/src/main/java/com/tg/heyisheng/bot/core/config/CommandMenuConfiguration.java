package com.tg.heyisheng.bot.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.core.dispatch.CommandMenuRegistrar;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.failover.TelegramApiMethodExecutor;
import com.tg.heyisheng.bot.core.interaction.MenuVisibility;
import com.tg.heyisheng.bot.core.permission.RoleSource;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 命令菜单的装配：把 {@code @BotCommand} 声明的命令清单**按权限分档**注册到 Telegram
 * （客户端里输入 {@code /} 时弹出的提示菜单）。
 *
 * <p>分档规则与「为什么接缝类不进客户端菜单」写在 {@link CommandMenuRegistrar} 的类 javadoc 里。
 * 本类只负责**分类**这一件事：把注册表里的命令分成「公开 / 管理 / 接缝」三类，
 * 连同已授权项交给 {@link CommandMenuRegistrar#planMenus}。
 *
 * <p><b>分类为什么在这里而不在注册器里</b>：判断「某命令是否被可见性接缝认领」需要
 * {@code MenuVisibility}（交互层的概念），而注册器只该做「按 scope 发清单」。
 * 分类放装配层，两边都不必知道对方的细节。
 *
 * <p>独立成一个配置类（不塞进 {@code TggCoreConfiguration}）是刻意的：后者被多处
 * {@code ApplicationContextRunner} 装配，往里加 bean 会波及那些既有测试
 * （本项目已踩过：新增依赖 JPA 仓库的 bean 会让 RbacWiringTest 四条整体起不来）。
 *
 * <p><b>默认开启</b>（{@code tgg.command-menu.enabled} 缺省即 true）：这是「让已有功能真正可见」
 * 的修复，不是可选增强——默认关闭会让命令继续在客户端里隐形。
 */
@Configuration
@ConditionalOnProperty(prefix = "tgg.command-menu", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class CommandMenuConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CommandMenuConfiguration.class);

    @Bean
    public CommandMenuRegistrar commandMenuRegistrar(CommandRegistry commandRegistry,
                                                     ObjectProvider<MenuVisibility> menuVisibilities,
                                                     RoleSource roleSource,
                                                     WebhookProperties properties,
                                                     ObjectMapper objectMapper) {
        // 接缝类（平台白名单）不进客户端菜单——Telegram 没有「全局按人」的 scope，见注册器 javadoc
        Set<String> seamCommands = menuVisibilities.stream()
                .flatMap(visibility -> visibility.commands().stream())
                .collect(Collectors.toSet());

        List<CommandMenuRegistrar.ScopedMenu> menus = CommandMenuRegistrar.planMenus(
                commandRegistry, seamCommands, roleSource.grants());
        log.info("客户端命令菜单：共 {} 档（默认档 + {} 个逐成员覆盖档，且各档内容一致）；"
                        + "客户端 / 菜单只含入口命令 {} 条；接缝类命令 {} 条不进客户端菜单"
                        + "（由 /menu 的可见性接缝呈现）。",
                menus.size(), menus.size() - 1,
                menus.isEmpty() ? 0 : menus.get(0).commands().size(), seamCommands.size());

        String token = properties.getBotToken();
        // 无 token 时传 null（由 registrar 负责 WARN 并跳过），而不是在这里静默不装配——
        // 「没注册」这件事必须在日志里说得出来。
        // 注意形状：Sender 返回 boolean，TelegramApiMethodExecutor#execute 的成败因此**不会**被丢弃。
        CommandMenuRegistrar.Sender sender = (token == null || token.isBlank())
                ? null
                : new TelegramApiMethodExecutor(new OkHttpClient(), objectMapper, token)::execute;
        return new CommandMenuRegistrar(menus, sender);
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

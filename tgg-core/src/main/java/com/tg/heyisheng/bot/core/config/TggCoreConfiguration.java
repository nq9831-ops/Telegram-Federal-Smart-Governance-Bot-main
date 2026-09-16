package com.tg.heyisheng.bot.core.config;

import com.tg.heyisheng.bot.core.dispatch.CommandDispatcher;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.dispatch.UpdateDispatcher;
import com.tg.heyisheng.bot.core.middleware.AuthenticationMiddleware;
import com.tg.heyisheng.bot.core.middleware.GroupConfigMiddleware;
import com.tg.heyisheng.bot.core.middleware.MiddlewareChain;
import com.tg.heyisheng.bot.core.permission.InMemoryRoleSource;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.RoleGrantParser;
import com.tg.heyisheng.bot.core.permission.RoleSource;
import com.tg.heyisheng.bot.core.ratelimit.InMemoryRateLimiter;
import com.tg.heyisheng.bot.core.ratelimit.RateLimitMiddleware;
import com.tg.heyisheng.bot.core.webhook.SecretTokenFilter;
import com.tg.heyisheng.bot.core.webhook.SecretTokenVerifier;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 模块一装配：把接入、中间件链、权限与分发串起来。
 *
 * <p>中间件顺序即执行顺序：<b>认证 → 群组配置加载 → 限流</b>。
 *
 * <p><b>权限校验不在中间件链里</b>，而在 {@link CommandDispatcher} 内按命令声明判定——
 * 中间件看不到「即将执行哪条命令」，无法得知该命令需要什么权限。
 */
@Configuration
@EnableConfigurationProperties(WebhookProperties.class)
public class TggCoreConfiguration {

    /** 用户维度：10 秒 20 条。 */
    private static final int USER_LIMIT = 20;
    /** 群组维度：10 秒 60 条。 */
    private static final int GROUP_LIMIT = 60;
    /** 全局维度：10 秒 200 条。 */
    private static final int GLOBAL_LIMIT = 200;
    private static final Duration WINDOW = Duration.ofSeconds(10);

    @Bean
    public CommandRegistry commandRegistry(List<CommandHandler> handlers) {
        return new CommandRegistry(handlers);
    }

    /**
     * 角色来源。生产授权入口：配置 {@code tgg.permission.admins}，
     * 格式 {@code <chatId>:<userId>[:role]}（逗号分隔），省略 role 时为 ADMIN。
     *
     * <p>这是内存实现（重启需重新读取配置，配置本身即持久化载体）。
     * 模块三/十一引入数据库后替换为持久化实现，判定逻辑不变。
     */
    @Bean
    public RoleSource roleSource(@Value("${tgg.permission.admins:}") String adminsSpec) {
        InMemoryRoleSource source = new InMemoryRoleSource();
        RoleGrantParser.apply(source, adminsSpec);
        return source;
    }

    @Bean
    public PermissionChecker permissionChecker(RoleSource roleSource) {
        return new PermissionChecker(roleSource);
    }

    @Bean
    public CommandDispatcher commandDispatcher(CommandRegistry registry,
                                               PermissionChecker permissionChecker) {
        // 必须注入真实判定器——用单参构造器会落到「恒最小权限」的兜底实现，
        // 使权限门控在生产中完全不生效（该实现仅供未装配场景兜底与单测使用）。
        return new CommandDispatcher(registry, permissionChecker);
    }

    @Bean
    public MiddlewareChain middlewareChain() {
        return new MiddlewareChain(List.of(
                new AuthenticationMiddleware(),
                new GroupConfigMiddleware(),
                new RateLimitMiddleware(
                        new InMemoryRateLimiter(USER_LIMIT, WINDOW),
                        new InMemoryRateLimiter(GROUP_LIMIT, WINDOW),
                        new InMemoryRateLimiter(GLOBAL_LIMIT, WINDOW))));
    }

    @Bean
    public UpdateDispatcher updateDispatcher(MiddlewareChain middlewareChain,
                                             CommandDispatcher commandDispatcher) {
        return new UpdateDispatcher(middlewareChain, commandDispatcher);
    }

    @Bean
    public SecretTokenVerifier secretTokenVerifier(WebhookProperties properties) {
        return new SecretTokenVerifier(properties.getSecret());
    }

    @Bean
    public FilterRegistrationBean<SecretTokenFilter> secretTokenFilter(
            SecretTokenVerifier verifier, WebhookProperties properties) {
        FilterRegistrationBean<SecretTokenFilter> registration =
                new FilterRegistrationBean<>(new SecretTokenFilter(verifier, properties.getPath()));
        registration.addUrlPatterns(properties.getPath());
        return registration;
    }
}

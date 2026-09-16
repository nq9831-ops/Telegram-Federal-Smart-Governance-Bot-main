package com.tg.heyisheng.bot.core.config;

import com.tg.heyisheng.bot.core.dispatch.CommandDispatcher;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.dispatch.UpdateDispatcher;
import com.tg.heyisheng.bot.core.middleware.AuthenticationMiddleware;
import com.tg.heyisheng.bot.core.middleware.GroupConfigMiddleware;
import com.tg.heyisheng.bot.core.middleware.MiddlewareChain;
import com.tg.heyisheng.bot.core.middleware.PermissionMiddleware;
import com.tg.heyisheng.bot.core.ratelimit.InMemoryRateLimiter;
import com.tg.heyisheng.bot.core.ratelimit.RateLimitMiddleware;
import com.tg.heyisheng.bot.core.webhook.SecretTokenFilter;
import com.tg.heyisheng.bot.core.webhook.SecretTokenVerifier;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 模块一装配：把接入、中间件链、分发串起来。
 *
 * <p>中间件顺序即执行顺序：<b>认证 → 群组配置加载 → 权限校验 → 限流</b>。
 * 限流放在链尾——先让认证/权限把明显无效的请求挡掉，避免无谓计数。
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

    @Bean
    public CommandDispatcher commandDispatcher(CommandRegistry registry) {
        return new CommandDispatcher(registry);
    }

    @Bean
    public MiddlewareChain middlewareChain() {
        return new MiddlewareChain(List.of(
                new AuthenticationMiddleware(),
                new GroupConfigMiddleware(),
                new PermissionMiddleware(Set.of()),
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

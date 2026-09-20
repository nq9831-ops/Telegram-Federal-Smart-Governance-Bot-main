package com.tg.heyisheng.bot.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.core.callback.CallbackHandler;
import com.tg.heyisheng.bot.core.dispatch.CommandDispatcher;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.failover.TelegramApiMethodExecutor;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.core.interaction.CallbackCommandBridge;
import com.tg.heyisheng.bot.core.interaction.MenuCallbackHandler;
import com.tg.heyisheng.bot.core.ratelimit.InMemoryRateLimiter;
import com.tg.heyisheng.bot.core.ratelimit.RateLimiter;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 交互卡片装配：入口面板（{@code /menu}）与危险操作确认卡。
 *
 * <p><b>默认开启</b>（{@code tgg.interaction.enabled} 缺省即 true）：与 {@code command-menu} 同口径——
 * 「让已有功能真正可见」是修复而非可选增强；默认关闭会让 29 条命令继续只能靠背。
 * 关闭后行为回到升级前（见 {@code CallbackCommandBridge} 不被装配 ⇒ 面板命令也不装配）。
 *
 * <p><b>路由不在这里</b>：{@code CallbackRouter} 由 {@link CallbackConfiguration} <b>无条件</b>装配。
 * 若把路由建在本类（带开关）里，关掉交互会让准入的「点验证」按钮一并失效——那是本设计
 * 最容易踩的一脚，故拆开。
 *
 * <p><b>为什么自建发送通道而不复用 {@code ModerationActionSender}</b>：那个 bean 在容器里必须
 * <b>唯一</b>（历史 P0：多一个同类型 bean 就让整个上下文起不来，回归护栏见 {@code AdmissionWiringTest}），
 * 且语义是「处置动作」。这里要的是「应答按钮」，语义不同，按 {@code CommandMenuConfiguration}
 * 的先例自建即可。
 */
@Configuration
@ConditionalOnProperty(prefix = "tgg.interaction", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class InteractionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InteractionConfiguration.class);

    /** 按钮触发的用户维度配额：与消息链同量级（10 秒 20 次）。 */
    private static final int USER_LIMIT = 20;
    private static final Duration WINDOW = Duration.ofSeconds(10);

    @Bean
    public CallbackCommandBridge callbackCommandBridge(CommandDispatcher commandDispatcher,
                                                       CommandRegistry commandRegistry,
                                                       GroupConfigService groupConfigService,
                                                       WebhookProperties webhookProperties,
                                                       ObjectMapper objectMapper) {
        String token = webhookProperties.getBotToken();
        Consumer<BotApiMethod<?>> proactiveSender = null;
        if (token == null || token.isBlank()) {
            // 不静默降级：说清代价（结果照旧送达，只是按钮可能转圈到超时）
            log.warn("未配置 TGG_BOT_TOKEN：交互卡片的按钮点击没有「应答」——命令结果仍会送达，"
                    + "但客户端按钮可能转圈到超时。注入 token 后自动恢复。");
        } else {
            proactiveSender =
                    new TelegramApiMethodExecutor(new OkHttpClient(), objectMapper, token)::execute;
        }
        RateLimiter callbackLimiter = new InMemoryRateLimiter(USER_LIMIT, WINDOW);
        return new CallbackCommandBridge(commandDispatcher, commandRegistry, groupConfigService,
                callbackLimiter, proactiveSender);
    }

    /**
     * Hub 按钮的处理器。
     *
     * <p>与准入的 {@code verify} 处理器并列——{@code CallbackRouter} 会把两个都收进来；
     * action 前缀不同（{@code menu} / {@code verify}），冲突时构造期即失败。
     */
    @Bean
    public CallbackHandler menuCallbackHandler(CallbackCommandBridge callbackCommandBridge) {
        return new MenuCallbackHandler(callbackCommandBridge);
    }
}

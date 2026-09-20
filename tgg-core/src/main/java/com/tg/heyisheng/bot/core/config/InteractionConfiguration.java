package com.tg.heyisheng.bot.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.core.callback.CallbackHandler;
import com.tg.heyisheng.bot.core.dispatch.CommandDispatcher;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.failover.TelegramApiMethodExecutor;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.core.interaction.CallbackCommandBridge;
import com.tg.heyisheng.bot.core.interaction.ConfirmCallbackHandler;
import com.tg.heyisheng.bot.core.interaction.ConfirmCancelCallbackHandler;
import com.tg.heyisheng.bot.core.interaction.ConfirmationStore;
import com.tg.heyisheng.bot.core.interaction.MenuCallbackHandler;
import com.tg.heyisheng.bot.core.interaction.MenuCatalog;
import com.tg.heyisheng.bot.core.interaction.MenuVisibility;
import com.tg.heyisheng.bot.core.interaction.ModerationMenuVisibility;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.ratelimit.InMemoryRateLimiter;
import com.tg.heyisheng.bot.core.ratelimit.RateLimiter;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;

import java.time.Clock;
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
     * 面板目录：可见性判定 + 分类分组（文本命令与按钮导航共用）。
     *
     * <p><b>注册表必须惰性取</b>：{@code CommandRegistry} 由全部 {@code CommandHandler} 构造，
     * 而 {@code /menu} 自身也是 handler——直接注入会成构造环。{@code ObjectProvider} 断开环，
     * 真正取用发生在请求时刻。
     *
     * <p><b>接缝集合同样用 {@code ObjectProvider}</b>：一个模块都没注册可见性接缝时（如只跑
     * core 的最小装配），这里是一次明确的空流，而不是「无候选 bean」导致的启动失败
     * ——与 {@code CallbackConfiguration} 收 {@code CallbackHandler} 的取舍一致。
     */
    @Bean
    public MenuCatalog menuCatalog(ObjectProvider<CommandRegistry> commandRegistry,
                                   PermissionChecker permissionChecker,
                                   ObjectProvider<MenuVisibility> menuVisibilities) {
        return new MenuCatalog(commandRegistry, permissionChecker, menuVisibilities.stream().toList());
    }

    /**
     * core 自己的可见性接缝：复核队列 / 合规证据（平台白名单类）。
     *
     * <p>上层模块（listing / federation）各自注册自己的接缝 bean，装配层无需知道它们的存在
     * ——照 {@code TeachGate} 的依赖倒置。
     */
    @Bean
    public MenuVisibility moderationMenuVisibility(ModerationReviewGuard moderationReviewGuard) {
        return new ModerationMenuVisibility(moderationReviewGuard);
    }

    /**
     * Hub 按钮的处理器。
     *
     * <p>与准入的 {@code verify} 处理器并列——{@code CallbackRouter} 会把两个都收进来；
     * action 前缀不同（{@code menu} / {@code verify}），冲突时构造期即失败。
     */
    @Bean
    public CallbackHandler menuCallbackHandler(CallbackCommandBridge callbackCommandBridge,
                                               MenuCatalog menuCatalog,
                                               GroupConfigService groupConfigService) {
        return new MenuCallbackHandler(callbackCommandBridge, menuCatalog, groupConfigService);
    }

    /**
     * 待确认操作登记簿。
     *
     * <p>它是 {@code CommandDispatcher} 的确认卡接缝（经 {@code ConfirmationRequests} 接口注入，
     * 见 {@code TggCoreConfiguration#commandDispatcher}）——本 bean 存在时危险命令才会被拦一道。
     */
    @Bean
    public ConfirmationStore confirmationStore() {
        return new ConfirmationStore(Clock.systemUTC());
    }

    /** 「✅ 确认执行」：消费令牌后带确认标记重新执行命令（门控照旧生效）。 */
    @Bean
    public CallbackHandler confirmCallbackHandler(ConfirmationStore confirmationStore,
                                                  CallbackCommandBridge callbackCommandBridge) {
        return new ConfirmCallbackHandler(confirmationStore, callbackCommandBridge);
    }

    /** 「❌ 取消」：消费令牌使其立即作废（否则取消之后原卡上的确认按钮仍然有效），并把卡片置为终态。 */
    @Bean
    public CallbackHandler confirmCancelCallbackHandler(ConfirmationStore confirmationStore,
                                                        CallbackCommandBridge callbackCommandBridge) {
        return new ConfirmCancelCallbackHandler(confirmationStore, callbackCommandBridge);
    }
}

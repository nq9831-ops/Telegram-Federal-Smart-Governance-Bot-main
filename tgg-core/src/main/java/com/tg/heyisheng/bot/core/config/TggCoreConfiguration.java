package com.tg.heyisheng.bot.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.dispatch.CommandDispatcher;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.dispatch.UpdateDispatcher;
import com.tg.heyisheng.bot.core.failover.TelegramApiMethodExecutor;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.core.middleware.AuthenticationMiddleware;
import com.tg.heyisheng.bot.core.middleware.GroupConfigMiddleware;
import com.tg.heyisheng.bot.core.middleware.MiddlewareChain;
import com.tg.heyisheng.bot.core.moderation.BuiltInRules;
import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import com.tg.heyisheng.bot.core.moderation.ModerationLayer;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRecorder;
import com.tg.heyisheng.bot.core.moderation.RegexLayer;
import com.tg.heyisheng.bot.core.permission.InMemoryRoleSource;
import com.tg.heyisheng.bot.core.privacy.MessageScrubber;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.RoleGrantParser;
import com.tg.heyisheng.bot.core.permission.RoleSource;
import com.tg.heyisheng.bot.core.ratelimit.InMemoryRateLimiter;
import com.tg.heyisheng.bot.core.ratelimit.RateLimitMiddleware;
import com.tg.heyisheng.bot.core.webhook.SecretTokenFilter;
import com.tg.heyisheng.bot.core.webhook.SecretTokenVerifier;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import com.tg.heyisheng.bot.core.wordfilter.BannedWordDetector;
import com.tg.heyisheng.bot.core.wordfilter.BannedWordService;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(TggCoreConfiguration.class);

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
    public MiddlewareChain middlewareChain(GroupConfigService groupConfigService) {
        return new MiddlewareChain(List.of(
                new AuthenticationMiddleware(),
                new GroupConfigMiddleware(groupConfigService),
                new RateLimitMiddleware(
                        new InMemoryRateLimiter(USER_LIMIT, WINDOW),
                        new InMemoryRateLimiter(GROUP_LIMIT, WINDOW),
                        new InMemoryRateLimiter(GLOBAL_LIMIT, WINDOW))));
    }

    /**
     * L1 审核层（正则）。
     *
     * <p>当前装载内置规则集；后续应改为按群从数据库加载并支持热更新，
     * 届时替换本 bean 的构造来源即可，流水线不受影响。
     */
    @Bean
    public ModerationLayer moderationLayer() {
        return new RegexLayer(BuiltInRules.all());
    }

    /**
     * 硬红线封禁等额外动作的主动发送通道。
     *
     * <p><b>无条件装配（不挂 {@code tgg.failover.enabled} 开关）</b>——硬红线冻结是安全关键功能，
     * 不能因默认关闭的 failover 而静默缺失（本项目反复踩的「默认关闭即静默降级」坑）。
     *
     * <p><b>token 缺失处理</b>：主动调用 Telegram API 需 bot token。若未配置 {@code TGG_BOT_TOKEN}，
     * 退化为空通道并<b>显式告警</b>——违规消息仍会被删除，只是封禁不可用；不静默降级。
     * 要启用硬红线封禁，请注入 TGG_BOT_TOKEN（见 docs/DEPLOYMENT-VERIFICATION.md）。
     */
    @Bean
    public ModerationActionSender moderationActionSender(ObjectMapper objectMapper,
                                                         WebhookProperties properties) {
        String token = properties.getBotToken();
        if (token == null || token.isBlank()) {
            log.warn("未配置 TGG_BOT_TOKEN：硬红线封禁不可用（违规消息仍会被删除）。"
                    + "要启用硬红线封禁，请注入 TGG_BOT_TOKEN。");
            return ModerationActionSender.noop();
        }
        TelegramApiMethodExecutor executor =
                new TelegramApiMethodExecutor(new OkHttpClient(), objectMapper, token);
        return executor::execute;
    }

    /**
     * 日志脱敏用的标识哈希器。
     *
     * <p><b>生产必须配置 {@code TGG_HASH_SALT}</b>：缺省时会退回开发兜底盐，
     * 而 Telegram userId 空间小、固定盐哈希可被枚举反推（属合规缺口）。
     * 这里显式告警——否则这类缺失会静默通过（本项目反复踩的「默认值掩盖配置遗漏」坑）。
     *
     * <p>注意：{@code IdHasher.usingDevFallbackSalt()} 的契约就是「供生产检查并告警」，
     * 此前只在测试里被调用、生产装配从未检查——本 bean 补上这一环。
     */
    @Bean
    public IdHasher idHasher() {
        IdHasher hasher = IdHasher.fromEnvironment();
        if (hasher.usingDevFallbackSalt()) {
            log.warn("未配置 TGG_HASH_SALT：审核日志的标识哈希使用开发兜底盐，可被枚举反推。"
                    + "生产必须配置该环境变量（见 docs/DEPLOYMENT-VERIFICATION.md C 段）。");
        }
        return hasher;
    }

    /**
     * 按群违禁词检测器（模块三 · 群组管理）。
     *
     * <p>与 L1 正则层<b>并列</b>，不实现 {@code ModerationLayer}——后者签名无 chatId，
     * 而违禁词是按群配置。
     */
    @Bean
    public BannedWordDetector bannedWordDetector(BannedWordService bannedWordService) {
        return new BannedWordDetector(bannedWordService);
    }

    @Bean
    public UpdateDispatcher updateDispatcher(MiddlewareChain middlewareChain,
                                             CommandDispatcher commandDispatcher,
                                             ModerationLayer moderationLayer,
                                             ModerationActionSender moderationActionSender,
                                             ModerationReviewRecorder moderationReviewRecorder,
                                             BannedWordDetector bannedWordDetector,
                                             IdHasher idHasher) {
        // 注入审核层：它必须在 scrub 之前拿到正文，产出的判定结果（不含原文）挂到上下文。
        // 注入主动处置通道：硬红线封禁走它（webhook 返回值只能执行一个方法，删除作返回值保底）。
        // 注入复核入队通道：中高风险命中入队待人工确认（fail-open，不阻断主链路）。
        // 注入违禁词检测器：按群词表拦截（与 L1 并列，取最严重）。
        return new UpdateDispatcher(middlewareChain, commandDispatcher, new MessageScrubber(), moderationLayer,
                idHasher, moderationActionSender, moderationReviewRecorder, bannedWordDetector);
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

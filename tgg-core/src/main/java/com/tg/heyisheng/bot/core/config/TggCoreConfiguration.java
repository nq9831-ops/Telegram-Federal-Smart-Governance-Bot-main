package com.tg.heyisheng.bot.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.admission.JoinVerificationService;
import com.tg.heyisheng.bot.core.callback.CallbackRouter;
import com.tg.heyisheng.bot.core.credit.CreditEventSink;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import com.tg.heyisheng.bot.core.dispatch.CommandDispatcher;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.dispatch.ConfirmationRequests;
import com.tg.heyisheng.bot.core.dispatch.UpdateDispatcher;
import com.tg.heyisheng.bot.core.failover.TelegramApiMethodExecutor;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.core.middleware.AuthenticationMiddleware;
import com.tg.heyisheng.bot.core.middleware.GroupConfigMiddleware;
import com.tg.heyisheng.bot.core.middleware.MiddlewareChain;
import com.tg.heyisheng.bot.core.notify.NotificationDispatcher;

import java.time.Clock;
import com.tg.heyisheng.bot.core.moderation.BuiltInRules;
import com.tg.heyisheng.bot.core.moderation.GroupTopicTagService;
import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import com.tg.heyisheng.bot.core.moderation.ModerationLayer;
import com.tg.heyisheng.bot.core.moderation.ModerationPipeline;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRecorder;
import com.tg.heyisheng.bot.core.moderation.RepeatedMessageDetector;
import com.tg.heyisheng.bot.core.moderation.SensitiveTopicDetector;
import com.tg.heyisheng.bot.core.moderation.SensitiveTopicGuard;
import com.tg.heyisheng.bot.core.moderation.SensitiveTopicStrikeService;
import com.tg.heyisheng.bot.core.moderation.RegexLayer;
import com.tg.heyisheng.bot.core.permission.ConfigBackedRoleSource;
import com.tg.heyisheng.bot.core.permission.InMemoryRoleSource;
import com.tg.heyisheng.bot.core.privacy.MessageScrubber;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.RoleGrantParser;
import com.tg.heyisheng.bot.core.permission.RoleSource;
import com.tg.heyisheng.bot.core.ratelimit.InMemoryRateLimiter;
import com.tg.heyisheng.bot.core.ratelimit.RateLimitMiddleware;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import com.tg.heyisheng.bot.core.wordfilter.BannedWordDetector;
import com.tg.heyisheng.bot.core.wordfilter.BannedWordService;
import com.tg.heyisheng.bot.core.wordfilter.TaughtRuleDetector;
import com.tg.heyisheng.bot.core.wordfilter.TaughtRuleRepository;
import com.tg.heyisheng.bot.core.wordfilter.TaughtRuleService;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

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
@EnableScheduling
public class TggCoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TggCoreConfiguration.class);

    /** 用户维度：10 秒 20 条。 */
    private static final int USER_LIMIT = 20;
    /** 群组维度：10 秒 60 条。 */
    private static final int GROUP_LIMIT = 60;
    /** 全局维度：10 秒 200 条。 */
    private static final int GLOBAL_LIMIT = 200;
    private static final Duration WINDOW = Duration.ofSeconds(10);

    /** 反刷屏：同一窗口内允许的同内容条数（第 N+1 条起判为重复刷屏）。 */
    private static final int REPEAT_ALLOWED = 3;
    /** 反刷屏：滑动窗口秒数。 */
    private static final int REPEAT_WINDOW_SECONDS = 60;

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
    public RoleSource roleSource(RuntimeConfigService runtimeConfig) {
        // 默认空串是 fail-closed（安全），但**静默**：门控会表现为「只拒不放」，
        // 所有管理命令（/addword、/enable 等，需 MANAGE_CONFIG）对任何人都不可用，
        // 而运维者不会去设一个没文档、也不报错的配置。这里显式告警，避免功能形同虚设。
        if (runtimeConfig.resolve(ConfigBackedRoleSource.KEY).orElse("").isBlank()) {
            log.warn("未配置 tgg.permission.admins（TGG_PERMISSION_ADMINS）：所有管理命令将对任何人不可用"
                    + "（门控只拒不放）。要启用，请在配置中心或环境变量中配置，格式 <chatId>:<userId>[:role]，"
                    + "请见 README 的配置表。");
        }
        // 热驱动：授权在**调用期**从配置解析（后台改授权无需重启）；
        // 启动期仍校验一次格式——fail-fast 不丢（见 ConfigBackedRoleSource 的 javadoc）。
        return new ConfigBackedRoleSource(runtimeConfig);
    }

    @Bean
    public PermissionChecker permissionChecker(RoleSource roleSource) {
        return new PermissionChecker(roleSource);
    }

    @Bean
    public CommandDispatcher commandDispatcher(CommandRegistry registry,
                                               PermissionChecker permissionChecker,
                                               ObjectProvider<ConfirmationRequests> confirmationRequests) {
        // 必须注入真实判定器——用单参构造器会落到「恒最小权限」的兜底实现，
        // 使权限门控在生产中完全不生效（该实现仅供未装配场景兜底与单测使用）。
        // 确认卡接缝走 ObjectProvider：交互模块未装配时取不到 → 传 null → 不拦截任何命令，
        // 于是「只装 core 配置」的 ApplicationContextRunner 型测试行为与升级前一致。
        return new CommandDispatcher(registry, permissionChecker, confirmationRequests.getIfAvailable());
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
     * 要启用硬红线封禁，请注入 TGG_BOT_TOKEN（见 README 配置表）。
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
     * <p><b>生产必须配置盐（{@code TGG_HASH_SALT} 环境变量，或配置文件 {@code tgg.hash.salt}）</b>：
     * 缺省时会退回开发兜底盐，而 Telegram userId 空间小、固定盐哈希可被枚举反推（属合规缺口）。
     * 这里显式告警——否则这类缺失会静默通过（本项目反复踩的「默认值掩盖配置遗漏」坑）。
     *
     * <p><b>盐由装配注入</b>（{@code @Value("${tgg.hash.salt:}")}）而非只读 {@code System.getenv}——
     * 否则把盐写进 {@code application.yml} 会被<b>静默忽略</b>并退回开发兜底盐（环境变量与外部配置
     * 都应生效，这是 Spring 配置的常规口径；此前只读 env 是一处口径不一致）。
     *
     * <p>注意：{@code IdHasher.usingDevFallbackSalt()} 的契约就是「供生产检查并告警」，
     * 此前只在测试里被调用、生产装配从未检查——本 bean 补上这一环。
     */
    @Bean
    public IdHasher idHasher(@Value("${tgg.hash.salt:}") String salt) {
        IdHasher hasher = new IdHasher(salt);
        if (hasher.usingDevFallbackSalt()) {
            log.warn("标识哈希使用开发兜底盐（可被枚举反推）：TGG_HASH_SALT / tgg.hash.salt 未配置，"
                    + "或恰等于兜底盐字面量。生产必须配置该配置项（见 README 配置表）。");
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

    /**
     * 按群教学规则服务（模块九 §10.3 的 {@code /teach}）。
     *
     * <p><b>无条件装配</b>（与违禁词同款）：本模块是核心审核能力的一部分，不受可选开关门控；
     * 群未使用该功能时它的开销是一次空列表缓存命中。时钟用系统 UTC；
     * 缓存 TTL 的可测试性由构造参数保证（测试直接 {@code new} 注入 {@code Clock.fixed}）。
     */
    @Bean
    public TaughtRuleService taughtRuleService(TaughtRuleRepository taughtRuleRepository) {
        return new TaughtRuleService(taughtRuleRepository, java.time.Clock.systemUTC());
    }

    /**
     * 按群教学规则检测器（热路径执行者）。
     *
     * <p>它<b>并列</b>于四层流水线而非实现 {@code ModerationLayer}——那个接口的
     * {@code inspect(String)} 没有 chatId，而教学规则是按群的（同 {@code BannedWordDetector} 的判断）。
     */
    @Bean
    public TaughtRuleDetector taughtRuleDetector(TaughtRuleService taughtRuleService) {
        return new TaughtRuleDetector(taughtRuleService);
    }


    /**
     * 成员入群时间采集（模块九 §10.3「入群时长」门槛的数据源）。
     *
     * <p><b>必须由部署侧配合才不空转</b>：Telegram 仅在 webhook 的 {@code allowed_updates} 显式包含
     * {@code chat_member}、<b>且</b> bot 是群管理员时，才会推送成员状态变化。二者缺一，表里一条都不会有
     * ——而且不会有任何报错（这就是「采集上线了却查不到数据」的形态）。部署方须确保
     * bot 为群管理员，且 webhook 的 {@code allowed_updates} 含 {@code chat_member}。
     *
     * <p><b>不挂功能开关</b>：它是「入群时长」门槛的唯一数据源，关掉等于让该门槛无声失效；
     * 而它自身的代价很小——只写一行窄数据，且退群即删（见 V15 迁移的说明）。
     */
    @Bean
    public com.tg.heyisheng.bot.core.membership.MemberJoinRecorder memberJoinRecorder(
            com.tg.heyisheng.bot.core.membership.MemberJoinObservationRepository memberJoinRepository,
            IdHasher idHasher) {
        return new com.tg.heyisheng.bot.core.membership.MemberJoinRecorder(
                memberJoinRepository, idHasher, Clock.systemUTC());
    }

    /**
     * 「入群时长」教学门槛（模块九 §10.3 原文第三条）——阈值可配，默认 30 天。
     *
     * <p>「无记录即放行」的口径与理由写在 {@code MembershipDurationTeachGate} 的 javadoc 里：
     * 历史入群时间在 Telegram 侧不可回收，拒绝无记录者等于永久误伤 bot 上线前的全部成员。
     */
    @Bean
    public com.tg.heyisheng.bot.core.wordfilter.TeachGate membershipDurationTeachGate(
            com.tg.heyisheng.bot.core.membership.MemberJoinObservationRepository memberJoinRepository,
            RuntimeConfigService runtimeConfig) {
        return new com.tg.heyisheng.bot.core.membership.MembershipDurationTeachGate(
                memberJoinRepository, Clock.systemUTC(), runtimeConfig);
    }

    /**
     * 「无违规」教学门槛（模块九 §10.3）——用 core 能看到的信号实现原文三条门槛里的这一条。
     *
     * <p><b>另两条的落地方式</b>：「信用分」那条的数据在 tgg-credit，core 处于依赖链底层看不到，
     * 故由模块七注册自己的 {@code TeachGate}（{@code NoDeductionTeachGate}）自动并入下方聚合器；
     * 「入群时长」由本类的 {@code membershipDurationTeachGate} 实现（数据来自 chat_member 采集）。
     */
    @Bean
    public com.tg.heyisheng.bot.core.wordfilter.TeachGate noViolationTeachGate(
            com.tg.heyisheng.bot.core.moderation.SensitiveTopicStrikeService strikeService) {
        return new com.tg.heyisheng.bot.core.wordfilter.NoViolationTeachGate(strikeService);
    }

    /**
     * 教学门槛聚合器（模块九 §10.3）——把<b>各模块</b>注册的 {@code TeachGate} 并成一个判定。
     *
     * <p>为何「聚合」而不是「core 直接实现」：门槛的数据源跨模块（core 有违规计数、tgg-credit 有信用分），
     * 而 core 看不到上层。各上层模块注册自己的 gate 即生效，core 无需知道它们存在——
     * 与 {@code CreditEventSink} 同一套依赖倒置。<b>未接线即零影响</b>：某模块未启用时它的 gate 不存在，
     * 聚合列表里就没有它，{@code /teach} 行为与升级前逐字一致。
     */
    @Bean
    public com.tg.heyisheng.bot.core.wordfilter.TeachEligibility teachEligibility(
            java.util.List<com.tg.heyisheng.bot.core.wordfilter.TeachGate> gates) {
        return com.tg.heyisheng.bot.core.wordfilter.TeachEligibility.composite(gates);
    }

    /**
     * 红线复核 SLA 催办（模块九 §10.6）——硬红线命中后须在 2 小时内完成人工确认，超时升级提醒。
     *
     * <p>SLA 与 cron 均可配（{@code tgg.moderation.redline-review-sla-hours} / {@code ...-cron}）。
     * 只催办、不自动动作：原文未规定「超时会怎样」，规格没写的地方不替规格做决定。
     */
    @Bean
    public com.tg.heyisheng.bot.core.moderation.RedLineReviewSla redLineReviewSla(
            com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository reviewRepository,
            com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard moderationReviewGuard,
            NotificationDispatcher notificationDispatcher,
            RuntimeConfigService runtimeConfig) {
        return new com.tg.heyisheng.bot.core.moderation.RedLineReviewSla(reviewRepository,
                moderationReviewGuard, notificationDispatcher, Clock.systemUTC(), runtimeConfig);
    }

    /**
     * 敏感话题分级与递进处置（模块九 §10.5）；不设置即关闭该能力。
     */
    @Bean
    @ConditionalOnProperty(prefix = "tgg.moderation", name = "sensitive-grading-enabled",
            havingValue = "true")
    public SensitiveTopicGuard sensitiveTopicGuard(GroupTopicTagService groupTopicTagService,
                                                   SensitiveTopicStrikeService strikeService,
                                                   ModerationActionSender actionSender,
                                                   NotificationDispatcher notificationDispatcher) {
        return new SensitiveTopicGuard(new SensitiveTopicDetector(groupTopicTagService),
                strikeService, actionSender, notificationDispatcher);
    }

    /**
     * 反刷屏：同一用户在同一群重复发相同内容的检测器（模块三）。
     *
     * <p>复用 {@link InMemoryRateLimiter} 做「群:用户:指纹」的滑动窗口计数——不另造轮子，
     * 并自动享有它的陈旧键驱逐（否则指纹 key 会随时间无界增长）。
     *
     * <p><b>阈值保守是刻意的</b>：活跃群里连发「收到」「好的」并不罕见，阈值过严会误删正常发言。
     * 默认「{@value #REPEAT_WINDOW_SECONDS} 秒内同内容 {@value #REPEAT_ALLOWED} 次」，
     * 超出即判刷屏（中风险，入复核队列供人工回看）。
     */
    @Bean
    public RepeatedMessageDetector repeatedMessageDetector() {
        return new RepeatedMessageDetector(new InMemoryRateLimiter(
                REPEAT_ALLOWED, Duration.ofSeconds(REPEAT_WINDOW_SECONDS)));
    }

    @Bean
    public UpdateDispatcher updateDispatcher(MiddlewareChain middlewareChain,
                                             CommandDispatcher commandDispatcher,
                                             ModerationLayer moderationLayer,
                                             ModerationPipeline moderationPipeline,
                                             ModerationActionSender moderationActionSender,
                                             ModerationReviewRecorder moderationReviewRecorder,
                                             BannedWordDetector bannedWordDetector,
                                             RepeatedMessageDetector repeatedMessageDetector,
                                             TaughtRuleDetector taughtRuleDetector,
                                             ObjectProvider<SensitiveTopicGuard> sensitiveTopicGuard,
                                             IdHasher idHasher,
                                             ObjectProvider<CallbackRouter> callbackRouter,
                                             ObjectProvider<JoinVerificationService> joinVerification,
                                             ObjectProvider<CreditEventSink> creditEventSink,
                                             ObjectProvider<com.tg.heyisheng.bot.core.membership.MemberJoinRecorder> memberJoinRecorder) {
        // 用 Builder 而非位置构造器：可选项已多到难以按位置阅读（见 Builder 的 javadoc）。
        // 回调路由与入群验证属模块四，受 tgg.admission.enabled 门控——未启用时取不到，传 null 即关闭该分支。
        return UpdateDispatcher.builder()
                .middlewareChain(middlewareChain)
                .commandDispatcher(commandDispatcher)
                .scrubber(new MessageScrubber())
                .moderationLayer(moderationLayer)
                .moderationPipeline(moderationPipeline)
                .idHasher(idHasher)
                .actionSender(moderationActionSender)
                .reviewRecorder(moderationReviewRecorder)
                .bannedWordDetector(bannedWordDetector)
                .repeatedMessageDetector(repeatedMessageDetector)
                .callbackRouter(callbackRouter.getIfAvailable())
                .joinVerificationService(joinVerification.getIfAvailable())
                .creditEventSink(creditEventSink.getIfAvailable())
                .memberJoinRecorder(memberJoinRecorder.getIfAvailable())
                .taughtRuleDetector(taughtRuleDetector)
                .sensitiveTopicGuard(sensitiveTopicGuard.getIfAvailable())
                .build();
    }

}

package com.tg.heyisheng.bot.admin;

import com.tg.heyisheng.bot.admin.approval.ApprovalCommandService;
import com.tg.heyisheng.bot.admin.approval.ApprovalOverdueJob;
import com.tg.heyisheng.bot.admin.approval.ApprovalQueryService;
import com.tg.heyisheng.bot.admin.identity.AdminAccountRepository;
import com.tg.heyisheng.bot.admin.identity.AdminAuthService;
import com.tg.heyisheng.bot.admin.identity.AdminBootstrap;
import com.tg.heyisheng.bot.admin.identity.AdminSessionFilter;
import com.tg.heyisheng.bot.admin.identity.AdminSessionRepository;
import com.tg.heyisheng.bot.admin.system.RestartAction;
import com.tg.heyisheng.bot.core.audit.AuditService;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import com.tg.heyisheng.bot.core.notify.NotificationDispatcher;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewDecisionService;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 模块十一 · Web 后台（审批中心 + 账号体系）装配。
 *
 * <p><b>默认不装配</b>：仅当 {@code tgg.admin.api-token} 有实际值时才产生任何 bean
 * （条件见 {@link AdminApiTokenCondition}；控制器同样挂该条件，否则上下文起不来）。
 *
 * <p><b>鉴权已从「共享 token + X-Operator-Id」改为「服务端会话」</b>：见
 * {@link AdminSessionFilter} 与 {@link AdminAuthService}。
 */
@Configuration
@Conditional(AdminApiTokenCondition.class)
@EnableConfigurationProperties(AdminProperties.class)
public class AdminConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AdminConfiguration.class);

    @PostConstruct
    void announce() {
        log.info("模块十一 · Web 后台已启用（审批中心 + 账号体系）；"
                + "鉴权＝会话令牌（Authorization: Bearer <会话令牌> → admin_sessions）。"
                + "超管由环境变量 tgg.admin.super-user / tgg.admin.super-password-hash 引导创建。");
    }

    @Bean
    public ApprovalQueryService approvalQueryService(ModerationReviewRepository reviewRepository,
                                                     RuntimeConfigService config) {
        return new ApprovalQueryService(reviewRepository, Clock.systemUTC(), config);
    }

    @Bean
    public ApprovalCommandService approvalCommandService(ModerationReviewRepository reviewRepository,
                                                         ModerationReviewDecisionService decisions,
                                                         AuditService audit) {
        return new ApprovalCommandService(reviewRepository, decisions, audit);
    }

    @Bean
    public ApprovalOverdueJob approvalOverdueJob(ApprovalQueryService queries,
                                                 ModerationReviewGuard moderationReviewGuard,
                                                 NotificationDispatcher notificationDispatcher,
                                                 RuntimeConfigService config) {
        return new ApprovalOverdueJob(queries, moderationReviewGuard, notificationDispatcher, config);
    }

    /**
     * 认证服务（登录 / 会话校验 / 登出 / 强制下线）。
     *
     * <p>时钟用 {@link Clock#systemUTC()}：本 bean 只在生产上下文装配（测试通过构造器注入固定时钟）。
     */
    @Bean
    public AdminAuthService adminAuthService(AdminAccountRepository accounts,
                                             AdminSessionRepository sessions,
                                             AdminProperties properties) {
        return new AdminAuthService(accounts, sessions, Clock.systemUTC(),
                properties.getLoginMaxFailedAttempts(),
                Duration.ofMinutes(properties.getLoginLockMinutes()));
    }

    /**
     * 超管引导（仅环境变量，无 API 能建超管）。
     *
     * <p>以 {@code @Bean} 返回实例即可——{@link AdminBootstrap#bootstrapSuperAdmin()} 标了
     * {@code @PostConstruct}，Spring 会在初始化该 bean 时调用。
     */
    @Bean
    public AdminBootstrap adminBootstrap(AdminAccountRepository accounts, AdminProperties properties) {
        return new AdminBootstrap(accounts, properties, Clock.systemUTC());
    }

    /**
     * Telegram 登录验签器——bot token 取自 {@code tgg.webhook.bot-token}（与 webhook 同一 token）。
     *
     * <p>未配置时仍创建（降级为不可用），使 {@code /admin/auth/telegram} 返回 503 而非让上下文起不来。
     */
    @Bean
    public com.tg.heyisheng.bot.admin.identity.TelegramLoginVerifier telegramLoginVerifier(
            org.springframework.core.env.Environment environment, AdminProperties properties) {
        String botToken = environment.getProperty("tgg.webhook.bot-token", "");
        return new com.tg.heyisheng.bot.admin.identity.TelegramLoginVerifier(
                botToken, Duration.ofHours(properties.getTgLoginMaxAgeHours()));
    }

    /** 账号管理服务（仅超管可经端点调用，见 {@code AccountAdminController}）。 */
    @Bean
    public com.tg.heyisheng.bot.admin.identity.AccountAdminService accountAdminService(
            AdminAccountRepository accounts,
            com.tg.heyisheng.bot.core.platform.PlatformGrantSource platformGrants,
            AdminAuthService adminAuthService) {
        return new com.tg.heyisheng.bot.admin.identity.AccountAdminService(
                accounts, platformGrants, adminAuthService, Clock.systemUTC());
    }

    /**
     * 会话门禁过滤器，只挂在 {@code /admin/*} 上。
     *
     * <p>不放到全局：{@code /webhook}（Telegram 回调）与 {@code /federation/penalty}（联邦入站）
     * 各有自己的鉴权，不该被本模块的会话门禁拦下。
     */
    @Bean
    public FilterRegistrationBean<AdminSessionFilter> adminSessionFilter(AdminAuthService auth) {
        FilterRegistrationBean<AdminSessionFilter> registration =
                new FilterRegistrationBean<>(new AdminSessionFilter(auth));
        registration.addUrlPatterns("/admin/*");
        return registration;
    }

    /**
     * 默认重启动作：**优雅退出**进程，交给外部监管进程拉起。
     *
     * <p>⚠️ 本 bean 只负责退出。<b>能否再起来取决于部署</b>：Docker {@code restart} 策略 /
     * systemd {@code Restart=always} / k8s Deployment。裸 {@code java -jar} 下退出即停服，
     * 故该动作由 {@code tgg.admin.restart-enabled}（默认 false）门控，默认不会触发。
     */
    @Bean
    public RestartAction restartAction(org.springframework.context.ApplicationContext applicationContext) {
        return () -> new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            log.warn("后台触发重启：应用即将退出——请确保有外部监管进程（Docker restart / systemd / k8s）"
                    + "将其拉起，否则服务不会自行恢复。");
            int code = org.springframework.boot.SpringApplication.exit(applicationContext, () -> 0);
            System.exit(code);
        }, "admin-restart").start();
    }
}

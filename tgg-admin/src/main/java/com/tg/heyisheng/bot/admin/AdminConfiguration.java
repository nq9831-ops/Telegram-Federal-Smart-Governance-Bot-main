package com.tg.heyisheng.bot.admin;

import com.tg.heyisheng.bot.admin.approval.ApprovalCommandService;
import com.tg.heyisheng.bot.admin.approval.ApprovalOverdueJob;
import com.tg.heyisheng.bot.admin.approval.ApprovalQueryService;
import com.tg.heyisheng.bot.admin.system.RestartAction;
import com.tg.heyisheng.bot.core.audit.AuditService;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import com.tg.heyisheng.bot.core.notify.NotificationDispatcher;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewDecisionService;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository;
import com.tg.heyisheng.bot.core.webhook.SecretTokenVerifier;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 模块十一 · Web 后台（审批中心）装配。
 *
 * <p><b>默认不装配</b>：仅当 {@code tgg.admin.api-token} 有实际值时才产生任何 bean
 * ——与模块四/七/八/九/十同一契约：未启用即对主链路零影响。
 * 条件实现见 {@link AdminApiTokenCondition}（它<b>同时挂在控制器上</b>：控制器是组件扫描注册的，
 * 不受本类条件约束，只在一边挂条件会让上下文起不来）。
 *
 * <p><b>本模块不定义「谁能审批」的第二套口径</b>：operator 白名单直接复用既有
 * {@link ModerationReviewGuard}（即 {@code TGG_MODERATION_REVIEWERS}），与 Telegram 端的
 * {@code /review_*} 完全同源。
 */
@Configuration
@Conditional(AdminApiTokenCondition.class)
@EnableConfigurationProperties(AdminProperties.class)
public class AdminConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AdminConfiguration.class);

    @PostConstruct
    void announce() {
        log.info("模块十一 · 审批中心已启用（GET /admin/approvals）；"
                + "鉴权＝Bearer token + X-Operator-Id（须在 TGG_MODERATION_REVIEWERS 白名单内）");
    }

    @Bean
    public ApprovalQueryService approvalQueryService(ModerationReviewRepository reviewRepository,
                                                     RuntimeConfigService config) {
        // 超时阈值改由 RuntimeConfigService 在调用期读取 → 热生效（后台改完无需重启）。
        return new ApprovalQueryService(reviewRepository, Clock.systemUTC(), config);
    }

    @Bean
    public ApprovalCommandService approvalCommandService(ModerationReviewRepository reviewRepository,
                                                         ModerationReviewDecisionService decisions,
                                                         AuditService audit) {
        return new ApprovalCommandService(reviewRepository, decisions, audit);
    }

    /**
     * 审批超时提醒（§12.1 第 4 步）——超过 24 小时提醒、超过 72 小时升级。
     *
     * <p>与 §10.6 的硬红线 2 小时 SLA <b>分工而不重叠</b>：那条只催硬红线，这条只催非硬红线
     * （见 {@link ApprovalOverdueJob}）。阈值与 cron 均可配
     * （{@code tgg.admin.overdue-remind-hours} / {@code ...-escalate-hours} / {@code ...-cron}）。
     */
    @Bean
    public ApprovalOverdueJob approvalOverdueJob(ApprovalQueryService queries,
                                                 ModerationReviewGuard moderationReviewGuard,
                                                 NotificationDispatcher notificationDispatcher,
                                                 RuntimeConfigService config) {
        return new ApprovalOverdueJob(queries, moderationReviewGuard, notificationDispatcher, config);
    }

    /**
     * 门禁过滤器，只挂在 {@code /admin/*} 上。
     *
     * <p>不放到全局：既有的 {@code /webhook}（Telegram 回调）与 {@code /federation/penalty}
     * （联邦入站，自验签）各有自己的鉴权，不该被本模块的 token 拦下。
     *
     * <p><b>刻意不把校验器注册成 bean</b>：core 已有一个同类型的 {@code secretTokenVerifier}
     * （校验 webhook 的 secret），再注册一个会让按类型注入的既有 {@code secretTokenFilter}
     * 出现两个候选而<b>整个上下文启动失败</b>（本波实测踩到：
     * {@code expected single matching bean but found 2: secretTokenVerifier,adminTokenVerifier}）。
     * 校验器是无状态工具，用本模块自己的 token 就地构造即可。
     */
    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilter(
            AdminProperties properties, ModerationReviewGuard moderationReviewGuard) {
        AdminAuthFilter filter = new AdminAuthFilter(
                new SecretTokenVerifier(properties.getApiToken()), moderationReviewGuard);
        FilterRegistrationBean<AdminAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/admin/*");
        return registration;
    }

    /**
     * 默认重启动作：**优雅退出**进程，交给外部监管进程拉起。
     *
     * <p>⚠️ 本 bean 只负责退出。<b>能否再起来取决于部署</b>：Docker {@code restart} 策略 /
     * systemd {@code Restart=always} / k8s Deployment。裸 {@code java -jar} 下退出即停服，
     * 故该动作由 {@code tgg.admin.restart-enabled}（默认 false）门控，默认不会触发。
     *
     * <p>退出前 sleep 500ms，让 202 响应先发出去（否则调用方拿到连接重置而非「已受理」）。
     * 抽成 {@link RestartAction} 接口，使测试可注入替身而**永不真退出**。
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

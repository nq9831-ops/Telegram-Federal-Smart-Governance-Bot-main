package com.tg.heyisheng.bot.admin;

import com.tg.heyisheng.bot.admin.approval.ApprovalCommandService;
import com.tg.heyisheng.bot.admin.approval.ApprovalQueryService;
import com.tg.heyisheng.bot.core.audit.AuditService;
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
import java.time.Duration;

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
                                                     AdminProperties properties) {
        return new ApprovalQueryService(reviewRepository, Clock.systemUTC(),
                Duration.ofHours(properties.getOverdueRemindHours()),
                Duration.ofHours(properties.getOverdueEscalateHours()));
    }

    @Bean
    public ApprovalCommandService approvalCommandService(ModerationReviewRepository reviewRepository,
                                                         ModerationReviewDecisionService decisions,
                                                         AuditService audit) {
        return new ApprovalCommandService(reviewRepository, decisions, audit);
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
}

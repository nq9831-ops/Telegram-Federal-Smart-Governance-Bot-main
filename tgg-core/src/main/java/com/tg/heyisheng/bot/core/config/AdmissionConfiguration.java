package com.tg.heyisheng.bot.core.config;

import com.tg.heyisheng.bot.common.exception.TggConfigException;
import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.admission.AdmissionProperties;
import com.tg.heyisheng.bot.core.admission.JoinVerificationService;
import com.tg.heyisheng.bot.core.admission.ObservationPeriodService;
import com.tg.heyisheng.bot.core.admission.PendingVerificationRegistry;
import com.tg.heyisheng.bot.core.admission.VerificationCallbackHandler;
import com.tg.heyisheng.bot.core.admission.VerificationTimeoutSweeper;
import com.tg.heyisheng.bot.core.callback.CallbackHandler;
import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

/**
 * 模块四 · 准入与验证的装配。
 *
 * <p><b>默认关闭</b>（{@code tgg.admission.enabled=true} 才启用）——入群验证会改变每个新成员的
 * 体验，不该在没人显式开启时自动生效。⚠️ 不设该值则本类完全不装配且**不报任何错**，
 * 故部署时必须显式设置该开关（默认关闭且不报错）。
 */
@Configuration
@EnableConfigurationProperties(AdmissionProperties.class)
@EnableScheduling
@ConditionalOnProperty(prefix = "tgg.admission", name = "enabled", havingValue = "true")
public class AdmissionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AdmissionConfiguration.class);

    private final WebhookProperties webhookProperties;

    public AdmissionConfiguration(WebhookProperties webhookProperties) {
        this.webhookProperties = webhookProperties;
    }

    /**
     * 启用准入即必须配置 bot token：
     * 验证消息必须能主动发出——否则会出现「登记了但消息发不出去」，新成员白白超时被踢的误伤。
     * 与其运行期静默误伤，不如启动期直接失败（与 failover 的 requireBotToken 同款）。
     */
    @PostConstruct
    void requireBotToken() {
        String token = webhookProperties.getBotToken();
        if (token == null || token.isBlank()) {
            throw new TggConfigException(
                    "启用 tgg.admission 时必须配置 TGG_BOT_TOKEN（入群验证需主动发送验证消息，否则新成员会被误踢）");
        }
    }

    @Bean
    public PendingVerificationRegistry pendingVerificationRegistry() {
        return new PendingVerificationRegistry(Clock.systemUTC());
    }

    /*
     * ⚠️ 这里**刻意不再定义** ModerationActionSender。
     *
     * 曾经此处有一个 `admissionActionSender` bean，与 TggCoreConfiguration 里**无条件装配**的
     * `moderationActionSender` 实现同一接口 → 容器里出现两个候选，任何按类型注入该接口的消费方
     * （ModerationReviewDecisionService 等）都会以「required a single bean, but 2 were found」
     * 让**整个上下文启动失败**——即「准入一开启，应用就起不来」。
     * 该缺陷 2026-09-19 在「全开实跑」时暴露（既有测试从不开此开关，故长期假绿），
     * 回归护栏见 `tgg-app/src/test/java/.../AdmissionWiringTest.java`。
     *
     * 两者职责本就相同（用 bot token 调 Bot API 发送一个方法），故共用 core 的那一个：
     * 本类的 {@link #requireBotToken()} 已保证「准入启用 ⇒ token 非空」，于是 core 的 sender
     * 必然是真实通道而非 noop 兜底——语义与原先逐字一致。
     */

    /**
     * 观察期：通过验证后的限时限制（默认 7 天，期满 Telegram 自动解禁）。
     * 周期由 {@code tgg.admission.observation-seconds} 控制，设 0 即不启用。
     */
    @Bean
    public ObservationPeriodService observationPeriodService(ModerationActionSender moderationActionSender,
                                                             AdmissionProperties properties) {
        return new ObservationPeriodService(moderationActionSender,
                Duration.ofSeconds(properties.getObservationSeconds()));
    }

    @Bean
    public JoinVerificationService joinVerificationService(PendingVerificationRegistry registry,
                                                           ModerationActionSender moderationActionSender,
                                                           IdHasher idHasher,
                                                           AdmissionProperties properties) {
        return new JoinVerificationService(registry, moderationActionSender, idHasher,
                Duration.ofSeconds(properties.getTimeoutSeconds()));
    }

    @Bean
    public VerificationTimeoutSweeper verificationTimeoutSweeper(PendingVerificationRegistry registry,
                                                                 ModerationActionSender moderationActionSender) {
        return new VerificationTimeoutSweeper(registry, moderationActionSender);
    }

    /**
     * 验证回调处理器。
     *
     * <p><b>只贡献处理器，不再自造 {@code CallbackRouter}</b>：路由已提升为**无条件装配**
     * （见 {@code CallbackConfiguration}）。若仍把路由建在本类里，本模块关闭时交互卡片的回调
     * 也会一起失去路由；反之若把路由建在交互开关下，则本模块关闭时「点验证」按钮失效。
     * 路由只认「有哪些处理器」，与各模块开关无关。
     *
     * <p>本 bean 随类级 {@code @ConditionalOnProperty(tgg.admission)} 门控——与它的依赖
     * （registry / idHasher / observationPeriodService）同生命周期，不会出现「处理器在、依赖不在」。
     */
    @Bean
    public CallbackHandler verificationCallbackHandler(PendingVerificationRegistry registry,
                                                       IdHasher idHasher,
                                                       ObservationPeriodService observationPeriodService) {
        log.info("准入验证已启用：已注册 verify 回调处理器");
        return new VerificationCallbackHandler(registry, idHasher, observationPeriodService);
    }
}

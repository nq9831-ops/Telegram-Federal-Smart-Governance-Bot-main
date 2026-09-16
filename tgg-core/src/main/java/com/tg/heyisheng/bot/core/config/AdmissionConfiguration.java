package com.tg.heyisheng.bot.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.common.exception.TggConfigException;
import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.admission.AdmissionProperties;
import com.tg.heyisheng.bot.core.admission.JoinVerificationService;
import com.tg.heyisheng.bot.core.admission.ObservationPeriodService;
import com.tg.heyisheng.bot.core.admission.PendingVerificationRegistry;
import com.tg.heyisheng.bot.core.admission.VerificationCallbackHandler;
import com.tg.heyisheng.bot.core.admission.VerificationTimeoutSweeper;
import com.tg.heyisheng.bot.core.callback.CallbackHandler;
import com.tg.heyisheng.bot.core.callback.CallbackRouter;
import com.tg.heyisheng.bot.core.failover.TelegramApiMethodExecutor;
import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import jakarta.annotation.PostConstruct;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * 模块四 · 准入与验证的装配。
 *
 * <p><b>默认关闭</b>（{@code tgg.admission.enabled=true} 才启用）——入群验证会改变每个新成员的
 * 体验，不该在没人显式开启时自动生效。⚠️ 不设该值则本类完全不装配且**不报任何错**，
 * 故必须写进部署清单（见 docs/DEPLOYMENT-VERIFICATION.md）。
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

    /** 准入链路的主动发送通道（验证消息、观察期限制、超时移出共用）。 */
    @Bean
    public ModerationActionSender admissionActionSender(ObjectMapper objectMapper) {
        TelegramApiMethodExecutor executor = new TelegramApiMethodExecutor(
                new OkHttpClient(), objectMapper, webhookProperties.getBotToken());
        return executor::execute;
    }

    /**
     * 观察期：通过验证后的限时限制（默认 7 天，期满 Telegram 自动解禁）。
     * 周期由 {@code tgg.admission.observation-seconds} 控制，设 0 即不启用。
     */
    @Bean
    public ObservationPeriodService observationPeriodService(ModerationActionSender admissionActionSender,
                                                             AdmissionProperties properties) {
        return new ObservationPeriodService(admissionActionSender,
                Duration.ofSeconds(properties.getObservationSeconds()));
    }

    @Bean
    public JoinVerificationService joinVerificationService(PendingVerificationRegistry registry,
                                                           ModerationActionSender admissionActionSender,
                                                           IdHasher idHasher,
                                                           AdmissionProperties properties) {
        return new JoinVerificationService(registry, admissionActionSender, idHasher,
                Duration.ofSeconds(properties.getTimeoutSeconds()));
    }

    @Bean
    public VerificationTimeoutSweeper verificationTimeoutSweeper(PendingVerificationRegistry registry,
                                                                 ModerationActionSender admissionActionSender) {
        return new VerificationTimeoutSweeper(registry, admissionActionSender);
    }

    @Bean
    public CallbackRouter callbackRouter(PendingVerificationRegistry registry,
                                         IdHasher idHasher,
                                         ObservationPeriodService observationPeriodService,
                                         AdmissionProperties properties) {
        List<CallbackHandler> handlers = List.of(
                new VerificationCallbackHandler(registry, idHasher, observationPeriodService));
        log.info("准入验证已启用：回调处理器 {} 个，验证时限 {} 秒",
                handlers.size(), properties.getTimeoutSeconds());
        return new CallbackRouter(handlers);
    }
}

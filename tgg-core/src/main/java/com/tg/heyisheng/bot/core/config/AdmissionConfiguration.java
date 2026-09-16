package com.tg.heyisheng.bot.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.common.exception.TggConfigException;
import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.admission.AdmissionProperties;
import com.tg.heyisheng.bot.core.admission.JoinVerificationService;
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

    @Bean
    public JoinVerificationService joinVerificationService(PendingVerificationRegistry registry,
                                                           ObjectMapper objectMapper,
                                                           IdHasher idHasher,
                                                           AdmissionProperties properties) {
        TelegramApiMethodExecutor executor = new TelegramApiMethodExecutor(
                new OkHttpClient(), objectMapper, webhookProperties.getBotToken());
        return new JoinVerificationService(registry, executor::execute, idHasher,
                Duration.ofSeconds(properties.getTimeoutSeconds()));
    }

    @Bean
    public VerificationTimeoutSweeper verificationTimeoutSweeper(PendingVerificationRegistry registry,
                                                                 ModerationActionSender sender) {
        return new VerificationTimeoutSweeper(registry, sender);
    }

    @Bean
    public CallbackRouter callbackRouter(PendingVerificationRegistry registry,
                                         IdHasher idHasher,
                                         AdmissionProperties properties) {
        List<CallbackHandler> handlers = List.of(new VerificationCallbackHandler(registry, idHasher));
        log.info("准入验证已启用：回调处理器 {} 个，验证时限 {} 秒",
                handlers.size(), properties.getTimeoutSeconds());
        return new CallbackRouter(handlers);
    }
}

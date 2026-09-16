package com.tg.heyisheng.bot.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.core.dispatch.UpdateDispatcher;
import com.tg.heyisheng.bot.core.failover.DefaultTelegramModeController;
import com.tg.heyisheng.bot.core.failover.HealthTracker;
import com.tg.heyisheng.bot.core.failover.PollingFallbackCoordinator;
import com.tg.heyisheng.bot.core.failover.TelegramApiWebhookHealthProbe;
import com.tg.heyisheng.bot.core.failover.TelegramModeController;
import com.tg.heyisheng.bot.core.failover.WebhookHealthProbe;
import com.tg.heyisheng.bot.core.failover.WebhookHealthScheduler;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.webhook.starter.TelegramBotsSpringWebhookApplication;

import java.time.Duration;

/**
 * 降级接收集的装配。
 *
 * <p><b>默认关闭</b>（{@code tgg.failover.enabled=true} 才启用）——因为它依赖真实 bot token
 * 与 Telegram 网络：在没有这些条件的环境里启用只会在启动时反复报错。
 *
 * <p>开关参数：
 * <ul>
 *   <li>{@code tgg.failover.probe-interval-ms}，默认 10000</li>
 *   <li>{@code tgg.failover.failure-threshold}，默认 3（10s × 3 = 30s，满足「30 秒内切换」）</li>
 *   <li>{@code tgg.failover.error-window-seconds}，默认 300</li>
 * </ul>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "tgg.failover", name = "enabled", havingValue = "true")
public class FailoverConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FailoverConfiguration.class);

    @Bean(destroyMethod = "close")
    public TelegramBotsLongPollingApplication telegramBotsLongPollingApplication() {
        return new TelegramBotsLongPollingApplication();
    }

    @Bean
    public OkHttpClient failoverOkHttpClient() {
        return new OkHttpClient();
    }

    @Bean
    public TelegramModeController telegramModeController(
            TelegramBotsSpringWebhookApplication webhookApplication,
            TelegramBotsLongPollingApplication pollingApplication,
            WebhookProperties properties,
            UpdateDispatcher updateDispatcher) {

        // 与 webhook 注册键保持一致：不含前导斜杠（切片 1 的教训，见 docs/LESSONS.md 坑 3）
        String botPathSegment = properties.getPath().replaceFirst("^/+", "");

        LongPollingUpdateConsumer consumer = updates -> {
            for (Update update : updates) {
                try {
                    updateDispatcher.dispatch(update);
                } catch (Exception ex) {
                    log.warn("长轮询模式下分发 update 失败：{}", ex.getClass().getSimpleName(), ex);
                }
            }
        };

        return new DefaultTelegramModeController(
                webhookApplication, pollingApplication, botPathSegment,
                properties.getBotToken(), consumer);
    }

    @Bean
    public HealthTracker webhookHealthTracker(
            @Value("${tgg.failover.failure-threshold:3}") int failureThreshold) {
        return new HealthTracker(failureThreshold);
    }

    @Bean
    public WebhookHealthProbe webhookHealthProbe(OkHttpClient failoverOkHttpClient,
                                                 ObjectMapper objectMapper,
                                                 WebhookProperties properties,
                                                 @Value("${tgg.failover.error-window-seconds:300}") long errorWindowSeconds) {
        return new TelegramApiWebhookHealthProbe(
                failoverOkHttpClient, objectMapper,
                properties.getBotToken(), Duration.ofSeconds(errorWindowSeconds));
    }

    @Bean
    public PollingFallbackCoordinator pollingFallbackCoordinator(HealthTracker webhookHealthTracker,
                                                                WebhookHealthProbe webhookHealthProbe,
                                                                TelegramModeController telegramModeController) {
        return new PollingFallbackCoordinator(webhookHealthTracker, webhookHealthProbe, telegramModeController);
    }

    @Bean
    public WebhookHealthScheduler webhookHealthScheduler(PollingFallbackCoordinator coordinator) {
        return new WebhookHealthScheduler(coordinator);
    }
}

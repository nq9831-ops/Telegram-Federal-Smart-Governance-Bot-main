package com.tg.heyisheng.bot.core.config;

import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import com.tg.heyisheng.bot.core.notify.DeferredNotificationFlusher;
import com.tg.heyisheng.bot.core.notify.DeferredNotificationJob;
import com.tg.heyisheng.bot.core.notify.DeferredNotificationRepository;
import com.tg.heyisheng.bot.core.notify.NotificationDispatcher;
import com.tg.heyisheng.bot.core.notify.NotificationPreferenceService;
import com.tg.heyisheng.bot.core.notify.NotificationRateLimiter;
import com.tg.heyisheng.bot.core.notify.NotificationSender;
import com.tg.heyisheng.bot.core.notify.TelegramNotificationSender;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 模块十 · 通知装配：三级分类、免打扰时段、延迟冲刷。
 *
 * <p><b>为什么从 {@code TggCoreConfiguration} 拆出来</b>：那一类曾装配 29 个 bean，
 * 覆盖命令与权限、审核、通知、保留、教学门槛、web 入口六个互不相关的域。按域拆开后
 * 每类可独立阅读，改动一个域不必通读其余部分。
 *
 * <p>拆分不影响装配：{@code @Configuration} 类之间靠方法参数互相注入，
 * Spring 不关心某个 bean 定义在哪个类里（只要仍在组件扫描范围内）。
 * {@link WebhookProperties} 仍由 {@code TggCoreConfiguration} 的
 * {@code @EnableConfigurationProperties} 注册。
 */
@Configuration
public class NotificationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NotificationConfiguration.class);

    /**
     * 通知分发器（模块十 §11.1：紧急 / 重要 / 普通三级 + 频率门）。
     *
     * <p>未注入 token 时退化为日志实现并打 <b>WARN</b>——不做静默，否则运维会以为
     * 「你被禁言了」这类权益通知真的送到了用户私聊。
     */
    @Bean
    public NotificationDispatcher notificationDispatcher(ModerationActionSender moderationActionSender,
                                                         NotificationPreferenceService notificationPreferences,
                                                         DeferredNotificationRepository deferredNotifications,
                                                         WebhookProperties webhookProperties) {
        NotificationSender sender;
        String botToken = webhookProperties.getBotToken();
        if (botToken == null || botToken.isBlank()) {
            log.warn("未配置 TGG_BOT_TOKEN：通知退化为日志实现——用户不会在自己的私聊里收到任何通知"
                    + "（含「你被禁言了」这类权益变动）。注入 token 后自动切换为真实投递。");
            sender = NotificationSender.logging();
        } else {
            sender = new TelegramNotificationSender(moderationActionSender);
        }
        // 免打扰（§11.1）：非紧急通知暂存到时段结束；紧急（封禁/解封）不受影响。
        return new NotificationDispatcher(sender, new NotificationRateLimiter(Clock.systemUTC()),
                notificationPreferences, deferredNotifications, Clock.systemUTC());
    }

    /**
     * 延迟通知冲刷器（§11.1：免打扰结束即发）。
     *
     * <p>用 {@code @Scheduled} 而非 Kafka——本项目明确不引消息队列（见 HANDOFF 非目标）。
     */
    @Bean
    public DeferredNotificationFlusher deferredNotificationFlusher(
            DeferredNotificationRepository deferredNotifications,
            NotificationPreferenceService notificationPreferences,
            NotificationDispatcher notificationDispatcher) {
        return new DeferredNotificationFlusher(deferredNotifications, notificationPreferences,
                notificationDispatcher, Clock.systemUTC());
    }

    @Bean
    public DeferredNotificationJob deferredNotificationJob(DeferredNotificationFlusher flusher) {
        return new DeferredNotificationJob(flusher);
    }
}

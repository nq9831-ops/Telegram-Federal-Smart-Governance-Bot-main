package com.tg.heyisheng.bot.web;

import com.tg.heyisheng.bot.core.dispatch.UpdateDispatcher;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.webhook.starter.SpringTelegramWebhookBot;

/**
 * 声明 TelegramBots 的 bot bean。
 *
 * <p>库的 {@code TelegramBotInitializer}（实现 {@code InitializingBean}）在启动时会
 * <b>自动收集容器内所有</b> {@code SpringTelegramWebhookBot} bean 并注册进库的
 * {@code @RestController}——所以这里只需声明 bean，无需手动注册。
 *
 * <p>（依据：references/TelegramBots/…/starter/TelegramBotInitializer.java:26）
 */
@Configuration
public class TggWebhookBotConfig {

    private static final Logger log = LoggerFactory.getLogger(TggWebhookBotConfig.class);

    @Bean
    public SpringTelegramWebhookBot tggWebhookBot(WebhookProperties properties,
                                                  UpdateDispatcher updateDispatcher) {
        // 关键：库把 botPath 同时用作「注册表 key」与「与 @PostMapping("/{botPath}") 路径段的比对值」，
        // 而 Spring 的 @PathVariable 取到的路径段【不含前导斜杠】。
        // 若此处直接传 "/webhook"，注册 key 是 "/webhook" 而请求查的是 "webhook"，
        // 库会取不到 bot、直接返回 null —— 表现为「HTTP 200 但 handler 永不执行」的静默断链。
        // 因此这里必须剥离前导斜杠；而 SecretTokenFilter 的 URL pattern 仍需含斜杠的完整路径。
        String botPathSegment = properties.getPath().replaceFirst("^/+", "");

        return SpringTelegramWebhookBot.builder()
                .botPath(botPathSegment)
                .updateHandler(update -> {
                    try {
                        return updateDispatcher.dispatch(update).orElse(null);
                    } catch (RuntimeException ex) {
                        // 运行时异常直接上抛，交由 @RestControllerAdvice 统一处理（仍须返回 200）
                        throw ex;
                    } catch (Exception ex) {
                        // 库的 updateHandler 是 Function，不能抛受检异常——包装后同样交给统一处理器
                        throw new IllegalStateException("更新分发失败", ex);
                    }
                })
                .setWebhook(() -> log.info("SetWebhook 尚未接入（需公网地址，属切片 2 范围）"))
                .deleteWebhook(() -> log.info("DeleteWebhook 尚未接入（属切片 2 范围）"))
                .build();
    }
}

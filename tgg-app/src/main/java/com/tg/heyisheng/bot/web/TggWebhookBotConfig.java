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
        return SpringTelegramWebhookBot.builder()
                .botPath(properties.getPath())
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

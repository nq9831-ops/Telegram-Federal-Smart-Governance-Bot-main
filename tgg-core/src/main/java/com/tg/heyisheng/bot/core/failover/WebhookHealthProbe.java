package com.tg.heyisheng.bot.core.failover;

/**
 * Webhook 健康探测。
 *
 * <p>真实实现会调用 Telegram 的 {@code getWebhookInfo} 并检查
 * {@code last_error_date} / {@code pending_update_count}；
 * 本机无 bot token 与公网地址时可用假实现替换，以验证上层决策逻辑。
 */
@FunctionalInterface
public interface WebhookHealthProbe {

    /** @return {@code true} 表示当前 webhook 健康 */
    boolean isHealthy();
}

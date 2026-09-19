package com.tg.heyisheng.bot.core.failover;

/**
 * Webhook 健康探测。
 *
 * <p>真实实现会调用 Telegram 的 {@code getWebhookInfo} 并检查
 * {@code last_error_date} / {@code url}；本机无 bot token 与公网地址时可用假实现替换，
 * 以验证上层决策逻辑。
 *
 * <p><b>返回三态而非布尔</b>：见 {@link WebhookHealth}——「探测通道故障」与「webhook 真的不健康」
 * 必须分开，否则一次网络抖动会被当成被探测对象的故障、触发不可逆的降级。
 */
@FunctionalInterface
public interface WebhookHealthProbe {

    /** @return 探测结果三态（{@link WebhookHealth}），永不为 {@code null} */
    WebhookHealth probe();
}

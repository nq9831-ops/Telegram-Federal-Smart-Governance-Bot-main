package com.tg.heyisheng.bot.core.failover;

/**
 * 抽象「切换接收模式」的两个动作。
 *
 * <p>之所以做成接口：真实的启停涉及 TelegramBots 的注册表与网络，
 * 而<b>降级决策逻辑</b>不该依赖它们——这样才能在无 token、无公网的环境下验证决策本身。
 */
public interface TelegramModeController {

    /** 停止 webhook 接收（注销 bot，不再处理入站 POST）。 */
    void stopWebhook();

    /** 启动长轮询接收。 */
    void startLongPolling();
}

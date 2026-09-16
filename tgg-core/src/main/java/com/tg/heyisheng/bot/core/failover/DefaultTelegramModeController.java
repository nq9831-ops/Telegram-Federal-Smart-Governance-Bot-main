package com.tg.heyisheng.bot.core.failover;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.webhook.starter.TelegramBotsSpringWebhookApplication;

/**
 * 真实的模式切换实现：停 webhook → 起长轮询。
 *
 * <p><b>关键细节</b>：{@code botPathSegment} 必须是不含前导斜杠的形式——
 * 库用它作为注册表的 key，而请求侧 {@code @PathVariable} 取到的路径段也不含斜杠。
 * 带斜杠会导致注销不到、出现"僵尸注册"（切片 1 踩过这个坑，见 docs/LESSONS.md 坑 3）。
 *
 * <p><b>无需手动 DeleteWebhook</b>：库的 {@code BotSession.createPollerTask()} 在启动轮询前
 * 会自行调用 DeleteWebhook（已核源码）。若将来换实现，这一点需重新确认。
 */
public class DefaultTelegramModeController implements TelegramModeController {

    private static final Logger log = LoggerFactory.getLogger(DefaultTelegramModeController.class);

    private final TelegramBotsSpringWebhookApplication webhookApplication;
    private final TelegramBotsLongPollingApplication pollingApplication;
    private final String botPathSegment;
    private final String botToken;
    private final LongPollingUpdateConsumer consumer;

    private volatile BotSession pollingSession;

    public DefaultTelegramModeController(TelegramBotsSpringWebhookApplication webhookApplication,
                                         TelegramBotsLongPollingApplication pollingApplication,
                                         String botPathSegment,
                                         String botToken,
                                         LongPollingUpdateConsumer consumer) {
        this.webhookApplication = webhookApplication;
        this.pollingApplication = pollingApplication;
        this.botPathSegment = botPathSegment;
        this.botToken = botToken;
        this.consumer = consumer;
    }

    @Override
    public void stopWebhook() {
        try {
            webhookApplication.unregisterBot(botPathSegment);
            log.info("降级：已注销 webhook bot [{}]", botPathSegment);
        } catch (TelegramApiException ex) {
            // 注销失败不阻断降级——长轮询启动时的 DeleteWebhook 会补上服务端的部分
            log.error("降级：注销 webhook bot [{}] 失败，仍继续启动长轮询", botPathSegment, ex);
        }
    }

    @Override
    public void startLongPolling() {
        try {
            pollingSession = pollingApplication.registerBot(botToken, consumer);
            log.info("降级：长轮询已启动");
        } catch (TelegramApiException ex) {
            throw new IllegalStateException("降级失败：长轮询无法启动", ex);
        }
    }

    /** 供诊断/测试：长轮询当前是否在运行。 */
    public boolean isPollingActive() {
        BotSession session = pollingSession;
        return session != null && session.isRunning();
    }
}

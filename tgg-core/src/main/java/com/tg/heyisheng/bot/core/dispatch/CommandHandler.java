package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;

/**
 * 命令处理器：接收更新上下文，产出要回给 Telegram 的 API 调用。
 */
@FunctionalInterface
public interface CommandHandler {

    /**
     * @param ctx 更新上下文（含 updateId / userId / chatId / command，<b>不含消息正文</b>）
     * @return 要执行的 Bot API 方法；返回 {@code null} 表示无需回复
     */
    BotApiMethod<?> handle(UpdateContext ctx) throws Exception;
}

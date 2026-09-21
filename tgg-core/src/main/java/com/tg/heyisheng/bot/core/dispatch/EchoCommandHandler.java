package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * 示例命令处理器：{@code /echo}（别名 {@code /ping}）→ 回复固定文本。
 *
 * <p><b>刻意不回显用户输入</b>——{@link UpdateContext} 不含消息正文（隐私约束），
 * 且回显会把正文重新写回 Telegram 与日志。本处理器只证明链路连通。
 */
@BotCommand(value = "echo", description = "连通性测试，回复固定文本", aliases = {"ping"},
        publicCommand = true, category = MenuCategory.SELF_SERVICE)
@Component
public class EchoCommandHandler implements CommandHandler {

    static final String REPLY_TEXT = DispatchMessages.ECHO_REPLY;

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        // TelegramBots 的 SendMessage 无无参构造器；此处用 (chatId, text) 形式。
        // chat_id 在 Bot API 中是字符串可空字段，故此处显式转换。
        return new SendMessage(String.valueOf(ctx.chatId()), REPLY_TEXT);
    }
}

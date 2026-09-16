package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.Optional;

/**
 * 命令分发器：从更新中解析命令，路由到对应处理器。
 *
 * <p><b>命令解析用库自带的 {@code Message.getCommand()}</b>——它基于 Telegram 服务端标注的
 * {@code MessageEntity}(bot_command) 类型，而非文本首 token 切分。因此
 * {@code /echo@MyBot}、命令前有其它文本等情形都能正确处理（自行切字符串会在这些边角出错）。
 */
public class CommandDispatcher {

    private final CommandRegistry registry;

    public CommandDispatcher(CommandRegistry registry) {
        this.registry = registry;
    }

    /**
     * 解析并路由命令。
     *
     * @return 处理器产出的 Bot API 调用；非命令消息、未知命令或处理器返回 null 时为空
     */
    public Optional<BotApiMethod<?>> dispatch(Update update) throws Exception {
        if (update == null || update.getMessage() == null) {
            return Optional.empty();
        }

        Message message = update.getMessage();
        String command = message.getCommand();
        if (command == null) {
            return Optional.empty();
        }

        Optional<CommandHandler> handler = registry.find(command);
        if (handler.isEmpty()) {
            return Optional.empty();
        }

        UpdateContext ctx = new UpdateContext(
                update.getUpdateId(),
                message.getFrom() == null ? null : message.getFrom().getId(),
                message.getChat() == null ? null : message.getChat().getId(),
                command);

        return Optional.ofNullable(handler.get().handle(ctx));
    }
}

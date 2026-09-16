package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.permission.Permission;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * {@code /delword <词>} —— 删除本群违禁词。需 {@link Permission#MANAGE_CONFIG}。
 */
@BotCommand(value = "delword", description = "删除本群违禁词（需管理员权限）",
        requiredPermission = Permission.MANAGE_CONFIG)
@Component
public class DelWordCommandHandler implements CommandHandler {

    static final String USAGE = "用法：/delword <违禁词>";
    static final String REMOVED = "已删除违禁词。";
    static final String NOT_FOUND = "本群没有该词。";

    private final BannedWordService service;

    public DelWordCommandHandler(BannedWordService service) {
        this.service = service;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        String word = ctx.commandArgs().orElse(null);
        if (word == null) {
            return new SendMessage(String.valueOf(ctx.chatId()), USAGE);
        }
        boolean removed = service.removeWord(ctx.chatId(), word);
        return new SendMessage(String.valueOf(ctx.chatId()), removed ? REMOVED : NOT_FOUND);
    }
}

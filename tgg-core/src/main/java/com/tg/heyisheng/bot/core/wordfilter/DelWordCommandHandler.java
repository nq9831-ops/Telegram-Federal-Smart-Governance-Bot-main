package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.Confirm;
import com.tg.heyisheng.bot.core.permission.Permission;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * {@code /delword <词>} —— 删除本群违禁词。需 {@link Permission#MANAGE_CONFIG}。
 *
 * <p><b>需确认</b>：误删一个词等于开一个漏拦窗口（该词此后不再被拦），而管理员未必立刻察觉——
 * 属于「后果不重但难以发现」的一类，用一次点击换确定性。
 */
@BotCommand(value = "delword", description = "删除本群违禁词（需管理员权限）",
        requiredPermission = Permission.MANAGE_CONFIG, confirm = Confirm.ALWAYS,
        category = MenuCategory.MODERATION)
@Component
public class DelWordCommandHandler implements CommandHandler {

    static final String USAGE = WordFilterMessages.DELWORD_USAGE;
    static final String REMOVED = WordFilterMessages.DELWORD_REMOVED;
    static final String NOT_FOUND = WordFilterMessages.DELWORD_NOT_FOUND;

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

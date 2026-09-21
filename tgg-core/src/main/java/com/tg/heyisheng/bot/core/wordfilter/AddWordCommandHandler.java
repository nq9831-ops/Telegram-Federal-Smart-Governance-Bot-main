package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.permission.Permission;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * {@code /addword <词>} —— 添加本群违禁词。需 {@link Permission#MANAGE_CONFIG}。
 *
 * <p><b>为什么需要它</b>：没有写入入口，词表只能靠直接改数据库维护，
 * 「违禁词」对管理员就形同虚设——这正是本项目反复踩的"接好了但没通电"。
 *
 * <p><b>操作数</b>来自 {@link UpdateContext#commandArgs()}（命令的受限例外，非消息正文，
 * 见其 javadoc）。回复刻意<b>不回显词本身</b>：减少一次无谓的消息往返，也避免把词反复写回。
 */
@BotCommand(value = "addword", description = "添加本群违禁词（需管理员权限）",
        requiredPermission = Permission.MANAGE_CONFIG, category = MenuCategory.MODERATION)
@Component
public class AddWordCommandHandler implements CommandHandler {

    static final String USAGE = WordFilterMessages.ADDWORD_USAGE;
    static final String ADDED = WordFilterMessages.ADDWORD_ADDED;
    static final String IGNORED = WordFilterMessages.ADDWORD_IGNORED;

    private final BannedWordService service;

    public AddWordCommandHandler(BannedWordService service) {
        this.service = service;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        String word = ctx.commandArgs().orElse(null);
        if (word == null) {
            return new SendMessage(String.valueOf(ctx.chatId()), USAGE);
        }
        boolean added = service.addWord(ctx.chatId(), word, ctx.userId());
        return new SendMessage(String.valueOf(ctx.chatId()), added ? ADDED : IGNORED);
    }
}

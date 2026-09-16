package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.permission.Permission;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;

/**
 * {@code /words} —— 查看本群违禁词。需 {@link Permission#MANAGE_CONFIG}。
 *
 * <p>回复里列出词表：这些是<b>管理配置</b>（管理员本人提交的），且命令有权限门控，
 * 因此回显不构成信息泄露。无参命令，不需要 {@code commandArgs}。
 */
@BotCommand(value = "words", description = "查看本群违禁词（需管理员权限）",
        requiredPermission = Permission.MANAGE_CONFIG)
@Component
public class WordsCommandHandler implements CommandHandler {

    static final String EMPTY = "本群暂无违禁词。";
    static final String PREFIX = "本群违禁词：";

    private final BannedWordService service;

    public WordsCommandHandler(BannedWordService service) {
        this.service = service;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        List<String> words = service.listWords(ctx.chatId());
        String reply = words.isEmpty() ? EMPTY : PREFIX + String.join("、", words);
        return new SendMessage(String.valueOf(ctx.chatId()), reply);
    }
}

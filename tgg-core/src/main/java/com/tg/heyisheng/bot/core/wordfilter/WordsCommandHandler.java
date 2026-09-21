package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
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
 *
 * <p><b>必须截断</b>：Bot API 单条消息上限 4096 字符，超长会<b>整条发送失败</b>——
 * 管理员会以为「没有词」而不是「词太多没显示出来」。词表由管理员逐条添加、
 * 数量无上限，故这里按字数截断并提示还有多少未显示（与 {@code ReviewListCommandHandler} /
 * {@code TaughtRulesCommandHandler} 同一范式）。
 */
@BotCommand(value = "words", description = "查看本群违禁词（需管理员权限）",
        requiredPermission = Permission.MANAGE_CONFIG, category = MenuCategory.MODERATION)
@Component
public class WordsCommandHandler implements CommandHandler {

    static final String EMPTY = WordFilterMessages.WORDS_EMPTY;
    static final String PREFIX = WordFilterMessages.WORDS_PREFIX;

    /** Telegram 单条消息硬上限（超长整条发送失败）。 */
    static final int TELEGRAM_TEXT_LIMIT = 4096;
    /** 为「已截断」提示预留的余量。 */
    private static final int TRUNCATION_RESERVE = 40;

    private final BannedWordService service;

    public WordsCommandHandler(BannedWordService service) {
        this.service = service;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        List<String> words = service.listWords(ctx.chatId());
        return new SendMessage(String.valueOf(ctx.chatId()), render(words));
    }

    /** 渲染词表；超上限时逐词截断并提示还有多少未显示（绝不产出超长文本）。 */
    static String render(List<String> words) {
        if (words.isEmpty()) {
            return EMPTY;
        }
        StringBuilder sb = new StringBuilder(PREFIX);
        int shown = 0;
        for (String word : words) {
            String piece = (shown == 0 ? "" : "、") + word;
            if (sb.length() + piece.length() > TELEGRAM_TEXT_LIMIT - TRUNCATION_RESERVE) {
                sb.append(WordFilterMessages.WORDS_TRUNCATED_PREFIX).append(words.size() - shown)
                        .append(WordFilterMessages.WORDS_TRUNCATED_SUFFIX);
                return sb.toString();
            }
            sb.append(piece);
            shown++;
        }
        return sb.toString();
    }
}

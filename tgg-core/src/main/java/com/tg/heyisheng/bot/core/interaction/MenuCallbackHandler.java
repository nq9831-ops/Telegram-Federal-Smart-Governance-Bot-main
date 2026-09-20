package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.core.callback.CallbackHandler;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

import java.util.Optional;

/**
 * Hub 按钮回调：解析 {@code menu:<chatId>:<cmd>}，交给 {@link CallbackCommandBridge}。
 *
 * <p><b>本类不做门控</b>——权限、群开关、限流全在桥 → {@code CommandDispatcher} 那一侧。
 * 这里只负责「把自己那份 data 解析对」与「格式非法时不猜」。
 *
 * <p><b>为什么不在这里拦「需要操作数的命令」</b>：各 handler 在 {@code commandArgs} 为空时
 * <b>自己就回用法文案</b>（如 {@code /delword} 无参回 {@code USAGE}）。让命令自己拥有用法文案，
 * 比在这里维护一份「哪些命令需要参数」的清单更不容易漂移——清单会随新命令悄悄过期。
 */
public class MenuCallbackHandler implements CallbackHandler {

    private static final String UNKNOWN = "未知操作。";

    private final CallbackCommandBridge bridge;

    public MenuCallbackHandler(CallbackCommandBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public String action() {
        return MenuCommandHandler.CALLBACK_ACTION;
    }

    @Override
    public Optional<BotApiMethod<?>> handle(CallbackQuery query) {
        if (query == null) {
            return Optional.empty();
        }
        Parsed parsed = parse(query.getData());
        if (parsed == null) {
            // 必须应答：否则旧卡上的按钮会一直转圈，用户以为坏了（同 CallbackRouter 的约定）
            return Optional.of(AnswerCallbackQuery.builder()
                    .callbackQueryId(query.getId())
                    .text(UNKNOWN)
                    .build());
        }
        return bridge.execute(query, parsed.chatId(), parsed.command(), null, false);
    }

    /**
     * 解析 {@code menu:<chatId>:<cmd>}；任何不合形即返回 {@code null}。
     *
     * <p>用 {@code split(":", -1)}：默认 {@code split(":")} 会丢尾部空串，
     * 于是 {@code menu:-100:}（命令名缺失）会被读成 2 段而非 3 段——本项目为此吃过一次亏，
     * 宁可显式保留空段再判空。
     */
    static Parsed parse(String data) {
        if (data == null) {
            return null;
        }
        String[] parts = data.split(":", -1);
        if (parts.length != 3 || !MenuCommandHandler.CALLBACK_ACTION.equals(parts[0])) {
            return null;
        }
        Long chatId;
        try {
            chatId = Long.valueOf(parts[1]);
        } catch (NumberFormatException ex) {
            return null;
        }
        return parts[2].isBlank() ? null : new Parsed(chatId, parts[2]);
    }

    /** 解析结果：作用域群 + 命令名。 */
    record Parsed(Long chatId, String command) {
    }
}

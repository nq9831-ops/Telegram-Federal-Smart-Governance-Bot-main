package com.tg.heyisheng.bot.core.admission;

import com.tg.heyisheng.bot.core.callback.CallbackHandler;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;

import java.util.Optional;

/**
 * 处理「点击验证」按钮。
 *
 * <p>data 形如 {@code verify:<chatId>:<userId>}。
 *
 * <p><b>安全不变量：点击者必须是 data 里指定的那个人</b>。
 * 按钮对全群可见，若不校验就得由"任何人替别人点一下"完成验证——那验证就形同虚设。
 * 这条不是防御性编程，是验证机制成立的前提。
 */
public class VerificationCallbackHandler implements CallbackHandler {

    static final String PASSED = "验证通过，欢迎加入。";
    static final String NOT_YOURS = "这不是你的验证按钮。";
    static final String EXPIRED = "验证已过期或已完成。";

    private final PendingVerificationRegistry registry;

    public VerificationCallbackHandler(PendingVerificationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String action() {
        return JoinVerificationService.CALLBACK_ACTION;
    }

    @Override
    public Optional<BotApiMethod<?>> handle(CallbackQuery query) {
        Long clickerId = query.getFrom() == null ? null : query.getFrom().getId();
        Long[] target = parseData(query.getData());

        if (target == null || clickerId == null) {
            return Optional.of(answer(query, EXPIRED));
        }
        Long chatId = target[0];
        Long targetUserId = target[1];

        // 安全：按钮对全群可见，必须确认点击者就是被验证者本人
        if (!clickerId.equals(targetUserId)) {
            return Optional.of(answer(query, NOT_YOURS));
        }
        // markVerified 只在确有登记时返回 true——重复点击/已过期都落到这里
        if (!registry.markVerified(chatId, targetUserId)) {
            return Optional.of(answer(query, EXPIRED));
        }
        return Optional.of(answer(query, PASSED));
    }

    private static AnswerCallbackQuery answer(CallbackQuery query, String text) {
        return AnswerCallbackQuery.builder()
                .callbackQueryId(query.getId())
                .text(text)
                .build();
    }

    /** 解析 {@code verify:<chatId>:<userId>}；非法返回 null。 */
    private static Long[] parseData(String data) {
        if (data == null) {
            return null;
        }
        String[] parts = data.split(":");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new Long[]{Long.valueOf(parts[1]), Long.valueOf(parts[2])};
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

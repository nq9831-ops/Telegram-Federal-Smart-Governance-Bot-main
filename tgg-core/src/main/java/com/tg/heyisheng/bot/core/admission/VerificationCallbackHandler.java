package com.tg.heyisheng.bot.core.admission;

import com.tg.heyisheng.bot.common.util.IdHasher;
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
    private final IdHasher idHasher;

    public VerificationCallbackHandler(PendingVerificationRegistry registry, IdHasher idHasher) {
        this.registry = registry;
        this.idHasher = idHasher;
    }

    @Override
    public String action() {
        return JoinVerificationService.CALLBACK_ACTION;
    }

    @Override
    public Optional<BotApiMethod<?>> handle(CallbackQuery query) {
        Long clickerId = query.getFrom() == null ? null : query.getFrom().getId();
        ParsedData parsed = parseData(query.getData());

        if (parsed == null || clickerId == null) {
            return Optional.of(answer(query, EXPIRED));
        }

        // 安全：按钮对全群可见，必须确认点击者就是被验证者本人。
        // data 里存的是 userId 的哈希（不明文暴露），故用同样方式哈希后比对。
        if (!idHasher.hash(clickerId).equals(parsed.userHash())) {
            return Optional.of(answer(query, NOT_YOURS));
        }
        // markVerified 只在确有**未过期**的登记时返回 true
        if (!registry.markVerified(parsed.chatId(), clickerId)) {
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

    /** {@code verify:<chatId>:<userHash>} 的解析结果。 */
    private record ParsedData(Long chatId, String userHash) {
    }

    /** 解析 data；非法返回 null。 */
    private static ParsedData parseData(String data) {
        if (data == null) {
            return null;
        }
        String[] parts = data.split(":");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new ParsedData(Long.valueOf(parts[1]), parts[2]);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

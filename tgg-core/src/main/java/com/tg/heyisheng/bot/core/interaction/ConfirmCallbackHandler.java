package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.core.callback.CallbackHandler;
import com.tg.heyisheng.bot.core.dispatch.ConfirmationRequests;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

import java.util.Optional;

/**
 * 确认卡的「✅ 确认执行」回调。
 *
 * <p><b>它做三件事：解析令牌 → 消费（校验发起者/时效/一次性）→ 带确认标记重新执行那条命令。</b>
 * 执行本身仍走 {@link CallbackCommandBridge}，因此权限、群开关、限流与命令白名单**全部照旧生效**——
 * 「确认」不是绕过门控的通行证，只是多加了一道。
 */
public class ConfirmCallbackHandler implements CallbackHandler {

    static final String EXPIRED = "该确认已失效（超时或已被使用），请重新发起。";

    private final ConfirmationStore store;
    private final CallbackCommandBridge bridge;

    public ConfirmCallbackHandler(ConfirmationStore store, CallbackCommandBridge bridge) {
        this.store = store;
        this.bridge = bridge;
    }

    @Override
    public String action() {
        return ConfirmationRequests.CONFIRM_ACTION;
    }

    @Override
    public Optional<BotApiMethod<?>> handle(CallbackQuery query) {
        if (query == null) {
            return Optional.empty();
        }
        String nonce = nonceOf(query.getData());
        if (nonce == null) {
            return Optional.of(answer(query, EXPIRED));
        }
        Optional<ConfirmationStore.PendingAction> action =
                store.consume(nonce, query.getFrom() == null ? null : query.getFrom().getId());
        if (action.isEmpty()) {
            // 三种情况共用一句提示：过期 / 已被使用 / 不是你的卡。
            // 刻意不区分——区分开来等于告诉试探者「这个令牌存在，只是不属于你」。
            return Optional.of(answer(query, EXPIRED));
        }
        ConfirmationStore.PendingAction pending = action.get();
        return bridge.execute(query, pending.chatId(), pending.command(), pending.args(), true);
    }

    /** 取 {@code cfm:<nonce>} / {@code cfn:<nonce>} 里的令牌；不合形返回 null。 */
    static String nonceOf(String data) {
        if (data == null) {
            return null;
        }
        String[] parts = data.split(":", -1);
        if (parts.length != 2 || parts[1].isBlank()) {
            return null;
        }
        return parts[1];
    }

    static AnswerCallbackQuery answer(CallbackQuery query, String text) {
        return AnswerCallbackQuery.builder()
                .callbackQueryId(query.getId())
                .text(text)
                .build();
    }
}

package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.core.callback.CallbackHandler;
import com.tg.heyisheng.bot.core.dispatch.ConfirmationRequests;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

import java.util.Optional;

/**
 * 确认卡的「❌ 取消」回调。
 *
 * <p><b>取消要做的事与确认同样重要：把令牌消费掉。</b>否则用户点了取消之后，
 * 那张卡上的「确认」按钮仍然有效——他会以为已经撤回了，实际没有。
 * 消费即作废，之后任何点击都得到「已失效」。
 */
public class ConfirmCancelCallbackHandler implements CallbackHandler {

    private static final String CANCELLED = "已取消。";

    private final ConfirmationStore store;

    public ConfirmCancelCallbackHandler(ConfirmationStore store) {
        this.store = store;
    }

    @Override
    public String action() {
        return ConfirmationRequests.CANCEL_ACTION;
    }

    @Override
    public Optional<BotApiMethod<?>> handle(CallbackQuery query) {
        if (query == null) {
            return Optional.empty();
        }
        String nonce = ConfirmCallbackHandler.nonceOf(query.getData());
        if (nonce == null) {
            return Optional.of(ConfirmCallbackHandler.answer(query, ConfirmCallbackHandler.EXPIRED));
        }
        Optional<ConfirmationStore.PendingAction> consumed =
                store.consume(nonce, query.getFrom() == null ? null : query.getFrom().getId());
        // 消费成功与否都回「已取消」：取消是幂等的、无副作用的动作，无需向用户暴露令牌状态机。
        if (consumed.isEmpty()) {
            return Optional.of(ConfirmCallbackHandler.answer(query, ConfirmCallbackHandler.EXPIRED));
        }
        return Optional.of(ConfirmCallbackHandler.answer(query, CANCELLED));
    }
}

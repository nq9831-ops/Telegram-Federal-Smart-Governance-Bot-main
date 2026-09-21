package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.core.callback.CallbackHandler;
import com.tg.heyisheng.bot.core.dispatch.ConfirmationRequests;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

import java.util.Optional;

/**
 * 确认卡的「❌ 取消」回调。
 *
 * <p><b>取消要做的事与确认同样重要：把令牌消费掉。</b>否则用户点了取消之后，
 * 那张卡上的「确认」按钮仍然有效——他会以为已经撤回了，实际没有。
 * 消费即作废，之后任何点击都得到「已失效」。
 *
 * <p><b>另有一件同样重要的事：让卡片看得出来「已取消」。</b>只回一个转瞬即逝的 toast 不够——
 * 卡片是持久状态，用户回头看它仍带着「✅ 确认执行」，会怀疑取消没生效、再点一次。
 * 故这里把卡片就地改成终态并**去掉按钮**；「停止转圈」的应答改走补充通道
 * （取消没有别的产出，返回槽就用来更新卡片）。
 */
public class ConfirmCancelCallbackHandler implements CallbackHandler {

    /** 卡片上的终态文案——与按钮上的「❌ 取消」呼应，一眼可辨。 */
    static final String CANCELLED_CARD = "❌ 已取消。";

    private final ConfirmationStore store;
    private final CallbackCommandBridge bridge;

    public ConfirmCancelCallbackHandler(ConfirmationStore store, CallbackCommandBridge bridge) {
        this.store = store;
        this.bridge = bridge;
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
        // 消费失败一律回「已失效」：取消是幂等的、无副作用的动作，无需向用户暴露令牌状态机。
        if (consumed.isEmpty()) {
            return Optional.of(ConfirmCallbackHandler.answer(query, ConfirmCallbackHandler.EXPIRED));
        }

        BotApiMethod<?> card =
                ConfirmCallbackHandler.terminalCard(consumed.get().chatId(), query, CANCELLED_CARD);
        if (card == null) {
            // 取不到消息 id（异常来源）时至少给个回执，不能什么都不做
            return Optional.of(ConfirmCallbackHandler.answer(query, InteractionMessages.CANCELLED));
        }
        bridge.acknowledge(query);
        return Optional.of(card);
    }
}

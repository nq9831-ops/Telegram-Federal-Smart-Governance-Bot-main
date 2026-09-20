package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.core.callback.CallbackHandler;
import com.tg.heyisheng.bot.core.dispatch.ConfirmationRequests;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
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
        // 卡片置为终态：**尽力而为**——唯一的返回槽要留给命令结果（那是不可丢的产出）。
        // 不更新的话，命令执行完之后卡片上的两个按钮还挂着，用户仍能点到。
        bridge.sendSupplement(terminalCard(pending.chatId(), query,
                "✅ 已确认，正在执行 /" + pending.command() + "…"));
        return bridge.execute(query, pending.chatId(), pending.command(), pending.args(), true);
    }

    /**
     * 把确认卡置为终态：换文案 + **显式空键盘**去按钮。
     *
     * <p>chatId 由调用方传入（确认卡就发在那个群里），不依赖回调里那条可选的消息字段
     * ——与 {@code CallbackCommandBridge} 的既有约定一致。
     *
     * <p>取不到消息 id 时返回 {@code null}——这是补充动作，没有可编辑的目标就跳过，
     * 不能因此让主流程（执行命令 / 回执）失败。
     */
    static BotApiMethod<?> terminalCard(long chatId, CallbackQuery query, String text) {
        Integer messageId = query.getMessage() == null ? null : query.getMessage().getMessageId();
        if (messageId == null) {
            return null;
        }
        return EditMessageText.builder()
                .chatId(String.valueOf(chatId))
                .messageId(messageId)
                .text(text)
                .replyMarkup(MenuView.noKeyboard())
                .build();
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

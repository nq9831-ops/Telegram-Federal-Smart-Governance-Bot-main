package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 确认卡的**终态**：点过之后卡片必须看得出来「已经用过了」。
 *
 * <p>只回一个转瞬即逝的 toast 不够——卡片是持久状态，用户回头看它仍带着「✅ 确认执行 / ❌ 取消」
 * 就会怀疑操作没生效、再点一次（那时才得到「已失效」）。故两个动作都把卡片就地改成终态并去掉按钮。
 *
 * <p>两处细节是这个类专门钉住的：
 * <ol>
 *   <li>去按钮必须传**空键盘**——{@code editMessageText} 不传 {@code replyMarkup} 会**保留**原按钮；</li>
 *   <li>「确认」的返回槽要留给命令结果（不可丢的产出），故它的卡片更新走**补充通道**（尽力而为）。</li>
 * </ol>
 */
class ConfirmCardCallbackTest {

    private static final long CHAT = -100L;
    private static final long USER = 42L;
    private static final int MESSAGE_ID = 77;
    private static final String COMMAND = "delword";
    /** 待确认动作里的操作数——确认卡复述的就是它。 */
    private static final String COMMAND_ARGS = "广告";

    private static UpdateContext confirmationSource() {
        return new UpdateContext(1, USER, CHAT, MESSAGE_ID, COMMAND, COMMAND_ARGS);
    }

    private static CallbackQuery click(String data) {
        return click(data, MESSAGE_ID);
    }

    private static CallbackQuery click(String data, Integer messageId) {
        CallbackQuery query = new CallbackQuery();
        query.setId("cb-1");
        query.setFrom(User.builder().id(USER).firstName("T").isBot(false).build());
        query.setData(data);
        if (messageId != null) {
            query.setMessage(Message.builder().messageId(messageId).build());
        }
        return query;
    }

    private static InlineKeyboardMarkup markupOf(BotApiMethod<?> method) {
        return (InlineKeyboardMarkup) ((EditMessageText) method).getReplyMarkup();
    }

    // ────────────────────────── 取消 ──────────────────────────

    @Test
    void cancelTurnsTheCardIntoATerminalStateWithoutButtons() {
        ConfirmationStore store = new ConfirmationStore(Clock.systemUTC());
        String nonce = store.issue(confirmationSource());
        CallbackCommandBridge bridge = mock(CallbackCommandBridge.class);
        ConfirmCancelCallbackHandler handler = new ConfirmCancelCallbackHandler(store, bridge);

        Optional<BotApiMethod<?>> result = handler.handle(click("cfn:" + nonce));

        assertThat(result).isPresent();
        EditMessageText card = (EditMessageText) result.get();
        assertThat(card.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(card.getMessageId()).isEqualTo(MESSAGE_ID);
        assertThat(card.getText()).isEqualTo(ConfirmCancelCallbackHandler.CANCELLED_CARD);
        assertThat(markupOf(card).getKeyboard())
                .as("必须显式传空键盘——不传 replyMarkup 会保留原按钮，用户仍能点到那张已作废的卡")
                .isEmpty();
        verify(bridge).acknowledge(any());
    }

    /** 取消必须消费令牌：否则用户以为撤回了，而卡上的「确认」仍然有效。 */
    @Test
    void cancelConsumesTheTokenSoTheCardBecomesUsable() {
        ConfirmationStore store = new ConfirmationStore(Clock.systemUTC());
        String nonce = store.issue(confirmationSource());
        ConfirmCancelCallbackHandler handler =
                new ConfirmCancelCallbackHandler(store, mock(CallbackCommandBridge.class));

        handler.handle(click("cfn:" + nonce));

        assertThat(store.consume(nonce, USER)).as("取消后令牌即作废").isEmpty();
    }

    /** 取不到消息 id（异常来源）时至少要有回执，不能什么都不做。 */
    @Test
    void cancelFallsBackToAToastWhenTheCardCannotBeEdited() {
        ConfirmationStore store = new ConfirmationStore(Clock.systemUTC());
        String nonce = store.issue(confirmationSource());
        ConfirmCancelCallbackHandler handler =
                new ConfirmCancelCallbackHandler(store, mock(CallbackCommandBridge.class));

        Optional<BotApiMethod<?>> result = handler.handle(click("cfn:" + nonce, null));

        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(AnswerCallbackQuery.class);
    }

    @Test
    void cancelBySecondClickReportsExpiredInsteadOfEditingAgain() {
        ConfirmationStore store = new ConfirmationStore(Clock.systemUTC());
        String nonce = store.issue(confirmationSource());
        ConfirmCancelCallbackHandler handler =
                new ConfirmCancelCallbackHandler(store, mock(CallbackCommandBridge.class));
        handler.handle(click("cfn:" + nonce));

        Optional<BotApiMethod<?>> second = handler.handle(click("cfn:" + nonce));

        assertThat(second.orElseThrow()).isInstanceOf(AnswerCallbackQuery.class);
        assertThat(((AnswerCallbackQuery) second.get()).getText())
                .isEqualTo(ConfirmCallbackHandler.EXPIRED);
    }

    // ────────────────────────── 确认 ──────────────────────────

    /**
     * 确认：命令结果占住返回槽（不可丢），卡片终态走补充通道。
     *
     * <p>顺序也钉住——卡片先置终态再执行，用户不会在「已执行」之后还看到一张可点的卡。
     */
    @Test
    void confirmSendsTheTerminalCardThroughTheSupplementChannel() {
        ConfirmationStore store = new ConfirmationStore(Clock.systemUTC());
        String nonce = store.issue(confirmationSource());
        CallbackCommandBridge bridge = mock(CallbackCommandBridge.class);
        SendMessage commandResult = new SendMessage(String.valueOf(CHAT), "已删除");
        when(bridge.execute(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(Optional.of(commandResult));
        ConfirmCallbackHandler handler = new ConfirmCallbackHandler(store, bridge);

        Optional<BotApiMethod<?>> result = handler.handle(click("cfm:" + nonce));

        assertThat(result).as("返回槽留给命令结果").containsSame(commandResult);

        ArgumentCaptor<BotApiMethod<?>> supplement = ArgumentCaptor.forClass(BotApiMethod.class);
        verify(bridge).sendSupplement(supplement.capture());
        EditMessageText card = (EditMessageText) supplement.getValue();
        assertThat(card.getMessageId()).isEqualTo(MESSAGE_ID);
        assertThat(card.getText()).contains("/" + COMMAND).contains("已确认");
        assertThat(markupOf(card).getKeyboard()).as("终态卡不再可点").isEmpty();
    }

    /** 非发起者点确认：连卡片都不该动（不该让旁人看到「已确认」的假象）。 */
    @Test
    void confirmBySomeoneElseNeitherEditsTheCardNorExecutes() {
        ConfirmationStore store = new ConfirmationStore(Clock.systemUTC());
        String nonce = store.issue(confirmationSource());
        CallbackCommandBridge bridge = mock(CallbackCommandBridge.class);
        ConfirmCallbackHandler handler = new ConfirmCallbackHandler(store, bridge);
        CallbackQuery other = click("cfm:" + nonce);
        other.setFrom(User.builder().id(999L).firstName("X").isBot(false).build());

        Optional<BotApiMethod<?>> result = handler.handle(other);

        assertThat(((AnswerCallbackQuery) result.orElseThrow()).getText())
                .isEqualTo(ConfirmCallbackHandler.EXPIRED);
        verify(bridge, never()).sendSupplement(any());
        verify(bridge, never()).execute(any(), any(), any(), any(), anyBoolean());
    }

    /** 令牌不可用时不打扰卡片——那张卡可能根本不属于本次点击。 */
    @Test
    void unknownTokenDoesNotTouchAnyCard() {
        ConfirmationStore store = new ConfirmationStore(Clock.systemUTC());
        CallbackCommandBridge bridge = mock(CallbackCommandBridge.class);
        ConfirmCallbackHandler handler = new ConfirmCallbackHandler(store, bridge);

        Optional<BotApiMethod<?>> result = handler.handle(click("cfm:not-a-real-nonce"));

        assertThat(result.orElseThrow()).isInstanceOf(AnswerCallbackQuery.class);
        verify(bridge, never()).sendSupplement(any());
    }

    @Test
    void nullQueryIsIgnored() {
        ConfirmationStore store = new ConfirmationStore(Clock.systemUTC());
        CallbackCommandBridge bridge = mock(CallbackCommandBridge.class);

        assertThat(new ConfirmCallbackHandler(store, bridge).handle(null)).isEmpty();
        assertThat(new ConfirmCancelCallbackHandler(store, bridge).handle(null)).isEmpty();
    }

    @Test
    void actionsAreTheExpectedPrefixes() {
        ConfirmationStore store = new ConfirmationStore(Clock.systemUTC());
        CallbackCommandBridge bridge = mock(CallbackCommandBridge.class);

        assertThat(new ConfirmCallbackHandler(store, bridge).action()).isEqualTo("cfm");
        assertThat(new ConfirmCancelCallbackHandler(store, bridge).action()).isEqualTo("cfn");
    }
}

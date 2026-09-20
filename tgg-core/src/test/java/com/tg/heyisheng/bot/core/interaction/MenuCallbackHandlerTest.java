package com.tg.heyisheng.bot.core.interaction;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Hub 按钮回调测试。
 *
 * <p>它只干两件事：**解析自己的 data 格式**，然后交给桥。
 * 门控不在这一层（桥 → CommandDispatcher），所以这里只钉「解析正确」与「格式非法不猜」。
 */
class MenuCallbackHandlerTest {

    private static final long CHAT = -100L;
    private static final long USER = 42L;

    private final CallbackCommandBridge bridge = mock(CallbackCommandBridge.class);
    private final MenuCallbackHandler handler = new MenuCallbackHandler(bridge);

    private static CallbackQuery click(String data) {
        CallbackQuery q = new CallbackQuery();
        q.setId("cb-1");
        q.setFrom(User.builder().id(USER).firstName("T").isBot(false).build());
        q.setData(data);
        return q;
    }

    @Test
    void parsesChatAndCommandThenDelegatesToBridge() {
        BotApiMethod<?> reply = new SendMessage(String.valueOf(CHAT), "词表");
        when(bridge.execute(any(), eq(CHAT), eq("words"), any(), eq(false)))
                .thenReturn(Optional.of(reply));

        Optional<BotApiMethod<?>> result = handler.handle(click("menu:" + CHAT + ":words"));

        assertThat(result).containsSame(reply);
        // 参数原样送达：chatId 与命令名解析正确、args 为 null、未经确认
        verify(bridge).execute(any(), eq(CHAT), eq("words"), eq(null), eq(false));
    }

    /** data 格式非法 → 回一句提示，且**不**打扰桥（不猜、不执行）。 */
    @Test
    void rejectsMalformedDataWithoutTouchingBridge() {
        for (String bad : new String[]{"menu", "menu:-100", "menu:abc:words", "other:-100:words", "menu:-100:"}) {
            MenuCallbackHandler fresh = new MenuCallbackHandler(mock(CallbackCommandBridge.class));

            Optional<BotApiMethod<?>> result = fresh.handle(click(bad));

            assertThat(result).as("非法 data 必须回提示（否则按钮一直转圈）：%s", bad).isPresent();
            assertThat(result.get()).isInstanceOf(AnswerCallbackQuery.class);
        }
    }

    @Test
    void actionIsMenu() {
        assertThat(handler.action()).isEqualTo("menu");
    }

    /** 无 data 的回调（异常来源）不得抛异常，也不得转到桥。 */
    @Test
    void nullDataIsRejectedQuietly() {
        CallbackCommandBridge untouched = mock(CallbackCommandBridge.class);
        Optional<BotApiMethod<?>> result = new MenuCallbackHandler(untouched).handle(click(null));

        assertThat(result).isPresent();
        verifyNoInteractions(untouched);
    }
}

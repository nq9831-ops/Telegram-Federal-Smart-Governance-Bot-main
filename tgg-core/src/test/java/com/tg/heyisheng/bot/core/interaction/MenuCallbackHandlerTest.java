package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigView;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Hub 按钮回调测试（v2：导航 + 执行两条路径）。
 *
 * <p><b>导航</b>（{@code nav:*}）是纯展示：重算可见性、**就地编辑**原卡片，绝不合成命令——
 * 因此不经过 {@code CommandDispatcher}，也就不会触发确认卡。
 * <b>执行</b>（{@code run:*} 与兼容的旧 3 段格式）一字不变地交给桥。
 */
class MenuCallbackHandlerTest {

    private static final long CHAT = -100L;
    private static final long USER = 42L;
    private static final int MESSAGE_ID = 5;

    @BotCommand(value = "words", description = "查看本群违禁词（需管理员权限）",
            requiredPermission = Permission.MANAGE_CONFIG, category = MenuCategory.MODERATION)
    static class WordsHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "词表");
        }
    }

    @BotCommand(value = "menu", description = "面板")
    static class MenuHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return new SendMessage(String.valueOf(ctx.chatId()), "menu");
        }
    }

    private final CallbackCommandBridge bridge = mock(CallbackCommandBridge.class);
    private final GroupConfigService groupConfigs = mock(GroupConfigService.class);

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CommandRegistry> providerOf(CommandRegistry registry) {
        ObjectProvider<CommandRegistry> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(registry);
        return provider;
    }

    private MenuCallbackHandler handler() {
        when(groupConfigs.findOrDefault(CHAT)).thenReturn(new GroupConfigView(CHAT, "群", true));
        MenuCatalog catalog = new MenuCatalog(providerOf(
                new CommandRegistry(List.of(new WordsHandler(), new MenuHandler()))),
                new PermissionChecker((chatId, userId) -> Role.ADMIN), List.of());
        return new MenuCallbackHandler(bridge, catalog, groupConfigs);
    }

    private static CallbackQuery click(String data) {
        CallbackQuery q = new CallbackQuery();
        q.setId("cb-1");
        q.setFrom(User.builder().id(USER).firstName("T").isBot(false).build());
        q.setData(data);
        q.setMessage(Message.builder().messageId(MESSAGE_ID).build());
        return q;
    }

    private static List<InlineKeyboardButton> buttonsOf(BotApiMethod<?> method) {
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup)
                ((EditMessageText) method).getReplyMarkup();
        return markup.getKeyboard().stream().flatMap(List::stream).toList();
    }

    @Test
    void parsesRunVerbAndDelegatesToBridge() {
        BotApiMethod<?> reply = new SendMessage(String.valueOf(CHAT), "词表");
        when(bridge.execute(any(), eq(CHAT), eq("words"), any(), eq(false)))
                .thenReturn(Optional.of(reply));

        Optional<BotApiMethod<?>> result = handler().handle(click("menu:" + CHAT + ":run:words"));

        assertThat(result).containsSame(reply);
        verify(bridge).execute(any(), eq(CHAT), eq("words"), eq(null), eq(false));
    }

    /** 回归护栏：升级前发出的卡片仍是 3 段格式，用户点它必须照旧执行。 */
    @Test
    void legacyThreeSegmentDataStillRunsCommand() {
        BotApiMethod<?> reply = new SendMessage(String.valueOf(CHAT), "词表");
        when(bridge.execute(any(), eq(CHAT), eq("words"), any(), eq(false)))
                .thenReturn(Optional.of(reply));

        Optional<BotApiMethod<?>> result = handler().handle(click("menu:" + CHAT + ":words"));

        assertThat(result).containsSame(reply);
        verify(bridge).execute(any(), eq(CHAT), eq("words"), eq(null), eq(false));
    }

    @Test
    void navigatesToCategoryByEditingTheCardInPlace() {
        Optional<BotApiMethod<?>> result = handler().handle(click("menu:" + CHAT + ":nav:moderation"));

        assertThat(result).isPresent();
        EditMessageText edited = (EditMessageText) result.get();
        assertThat(edited.getMessageId()).as("就地编辑原卡片，而不是发新消息").isEqualTo(MESSAGE_ID);
        assertThat(edited.getText()).contains("内容审核");
        assertThat(buttonsOf(edited)).extracting(InlineKeyboardButton::getCallbackData)
                .containsExactly("menu:" + CHAT + ":run:words", "menu:" + CHAT + ":nav:home");
        verify(bridge).acknowledge(any());
    }

    @Test
    void returnsToHomeOnHomeKey() {
        Optional<BotApiMethod<?>> result = handler().handle(click("menu:" + CHAT + ":nav:home"));

        assertThat(buttonsOf(result.orElseThrow())).extracting(InlineKeyboardButton::getCallbackData)
                .containsExactly("menu:" + CHAT + ":nav:moderation");
    }

    /** 非法分类 key 不该得到一张「空分类页」——回主页，且不发死按钮。 */
    @Test
    void unknownCategoryKeyFallsBackToHome() {
        Optional<BotApiMethod<?>> result = handler().handle(click("menu:" + CHAT + ":nav:bogus"));

        assertThat(buttonsOf(result.orElseThrow())).extracting(InlineKeyboardButton::getCallbackData)
                .containsExactly("menu:" + CHAT + ":nav:moderation");
    }

    /** data 格式非法 → 回一句提示，且**不**打扰桥（不猜、不执行）。 */
    @Test
    void rejectsMalformedDataWithoutTouchingBridge() {
        for (String bad : new String[]{"menu", "menu:-100", "menu:abc:words", "other:-100:words",
                "menu:-100:", "menu:-100:nav:", "menu:-100:bogus:words", "menu:-100:nav:x:y"}) {
            CallbackCommandBridge untouched = mock(CallbackCommandBridge.class);

            Optional<BotApiMethod<?>> result =
                    new MenuCallbackHandler(untouched, null, groupConfigs).handle(click(bad));

            assertThat(result).as("非法 data 必须回提示（否则按钮一直转圈）：%s", bad).isPresent();
            assertThat(result.get()).isInstanceOf(AnswerCallbackQuery.class);
            verifyNoInteractions(untouched);
        }
    }

    @Test
    void actionIsMenu() {
        assertThat(handler().action()).isEqualTo("menu");
    }

    /** 无 data 的回调（异常来源）不得抛异常，也不得转到桥。 */
    @Test
    void nullDataIsRejectedQuietly() {
        CallbackCommandBridge untouched = mock(CallbackCommandBridge.class);
        Optional<BotApiMethod<?>> result =
                new MenuCallbackHandler(untouched, null, groupConfigs).handle(click(null));

        assertThat(result).isPresent();
        verifyNoInteractions(untouched);
    }
}

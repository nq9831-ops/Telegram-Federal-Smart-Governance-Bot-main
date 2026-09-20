package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MenuView} 的渲染：按钮文案与回调 {@code data} 的形状。
 *
 * <p>{@code data} 的长度断言是**硬约束**：Telegram 对 {@code callback_data} 限 64 字节，
 * 超限**不报错、只是点击无响应**——只能靠断言守住，不能靠试。
 */
class MenuViewTest {

    private static final long CHAT = -100L;
    private static final long LONG_CHAT = -1001234567890123L;

    private static List<InlineKeyboardButton> buttons(InlineKeyboardMarkup markup) {
        return markup.getKeyboard().stream().flatMap(List::stream).toList();
    }

    @Test
    void labelIncludesDescription() {
        assertThat(MenuView.label("words", "查看本群违禁词（需管理员权限）"))
                .isEqualTo("/words — 查看本群违禁词");
    }

    /** 没写描述的注册项不能因此变成空按钮——退化为只显示命令名。 */
    @Test
    void labelFallsBackToBareNameWithoutDescription() {
        assertThat(MenuView.label("nodesc", "")).isEqualTo("/nodesc");
        assertThat(MenuView.label("nodesc", null)).isEqualTo("/nodesc");
    }

    @Test
    void homeKeyboardListsOnlyProvidedCategoriesWithCounts() {
        Map<MenuCategory, List<String>> grouped = new LinkedHashMap<>();
        grouped.put(MenuCategory.MODERATION, List.of("words", "addword"));
        grouped.put(MenuCategory.REVIEW, List.of("review_list"));

        InlineKeyboardMarkup markup = MenuView.homeKeyboard(CHAT, grouped);

        assertThat(buttons(markup)).extracting(InlineKeyboardButton::getText)
                .containsExactly("内容审核（2）", "复核合规（1）");
        assertThat(buttons(markup)).extracting(InlineKeyboardButton::getCallbackData)
                .containsExactly("menu:" + CHAT + ":nav:moderation", "menu:" + CHAT + ":nav:review");
    }

    @Test
    void categoryKeyboardEndsWithBackButton() {
        InlineKeyboardMarkup markup = MenuView.categoryKeyboard(CHAT, List.of("words"),
                Map.of("words", "查看本群违禁词（需管理员权限）"));

        List<InlineKeyboardButton> buttons = buttons(markup);
        assertThat(buttons).hasSize(2);
        assertThat(buttons.get(0).getCallbackData()).isEqualTo("menu:" + CHAT + ":run:words");
        assertThat(buttons.get(1).getText()).isEqualTo(MenuView.BACK_LABEL);
        assertThat(buttons.get(1).getCallbackData()).isEqualTo("menu:" + CHAT + ":nav:home");
    }

    /** 最坏情况：最长 chatId + 最长分类 key + 最长命令名，都必须 ≤64 字节。 */
    @Test
    void callbackDataStaysWithinTelegramLimit() {
        Map<MenuCategory, List<String>> grouped = new LinkedHashMap<>();
        for (MenuCategory category : MenuCategory.values()) {
            grouped.put(category, List.of("merchant_settle"));
        }

        List<String> data = new java.util.ArrayList<>();
        buttons(MenuView.homeKeyboard(LONG_CHAT, grouped))
                .forEach(b -> data.add(b.getCallbackData()));
        buttons(MenuView.categoryKeyboard(LONG_CHAT, List.of("merchant_settle"), Map.of()))
                .forEach(b -> data.add(b.getCallbackData()));

        assertThat(data).isNotEmpty().allSatisfy(d ->
                assertThat(d.length()).as("callback_data 上限 64 字节：%s", d).isLessThanOrEqualTo(64));
    }

    @Test
    void categoryTextNamesTheCategory() {
        assertThat(MenuView.categoryText(MenuCategory.LISTING)).contains("收录商家");
    }
}

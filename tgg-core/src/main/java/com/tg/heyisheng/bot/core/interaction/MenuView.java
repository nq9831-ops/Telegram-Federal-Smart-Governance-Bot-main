package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.core.dispatch.CommandDescriptions;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /menu} 的键盘渲染（主页 / 分类页）与回调 {@code data} 编解码。
 *
 * <p>从 {@code MenuCommandHandler} 迁出，让**文本命令**与**按钮回调**两条入口共用同一套渲染
 * ——否则两处的按钮文案与 data 格式迟早漂移（项目已有「两处各写一份条件就会漂移」的教训）。
 *
 * <p><b>{@code data} 形状</b>（均在 Telegram 的 64 字节上限内）：
 * <ul>
 *   <li>分类导航：{@code menu:<chatId>:nav:<categoryKey>}，主页含 {@code nav:home}；</li>
 *   <li>执行命令：{@code menu:<chatId>:run:<cmd>}。</li>
 * </ul>
 * 解析侧另有**兼容**旧格式 {@code menu:<chatId>:<cmd>}（见 {@code MenuCallbackHandler.parse}）
 * ——用户手机上已发出的旧卡片不该因为一次升级而失效。
 */
public final class MenuView {

    /** 「返回主页」按钮文案。 */
    static final String BACK_LABEL = "← 返回";

    /** 导航动词（分类页 / 主页之间的跳转）。 */
    static final String NAV_VERB = "nav";

    /** 执行动词（点击命令按钮）。 */
    static final String RUN_VERB = "run";

    /** 主页的导航 key。 */
    static final String HOME_KEY = "home";

    private MenuView() {
    }

    /**
     * 主页文案。
     *
     * <p>写清「为什么只看到这些」：面板按**当前用户在本群的真实权限**过滤过，不是命令缺失。
     */
    static String homeText() {
        return "可用功能（只列出你在此群能用的）：\n选一个分类查看，点按钮直接执行。";
    }

    /** 分类页文案：说清这是哪一类、以及「需填参数的命令会提示用法」这一过渡行为。 */
    static String categoryText(MenuCategory category) {
        return "「" + category.title() + "」—— 点按钮直接执行；需填参数的命令会提示用法。";
    }

    /** 分类导航按钮的 {@code data}。 */
    static String navData(long chatId, String key) {
        return MenuCommandHandler.CALLBACK_ACTION + ":" + chatId + ":" + NAV_VERB + ":" + key;
    }

    /**
     * 空键盘：把卡片置为终态（不再可点）时**必须显式传它**。
     *
     * <p>Bot API 的 {@code editMessageText} 在**不传** {@code reply_markup} 时会**保留**原按钮——
     * 于是「卡片置为终态」会变成「文字变了、按钮还在」，用户仍能点到已经失效的按钮。
     */
    public static InlineKeyboardMarkup noKeyboard() {
        return InlineKeyboardMarkup.builder().keyboard(List.of()).build();
    }

    /** 命令按钮的 {@code data}。 */
    static String runData(long chatId, String command) {
        return MenuCommandHandler.CALLBACK_ACTION + ":" + chatId + ":" + RUN_VERB + ":" + command;
    }

    /**
     * 主页键盘：**每个非空分类一个按钮**，文案＝分类名（含条数）；主页即根，无返回按钮。
     *
     * <p>条数写在按钮上不是装饰：面板的卖点是「可发现性」，让管理员点进去之前就知道值不值得点。
     */
    static InlineKeyboardMarkup homeKeyboard(long chatId, Map<MenuCategory, List<String>> grouped) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Map.Entry<MenuCategory, List<String>> entry : grouped.entrySet()) {
            rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text(entry.getKey().title() + "（" + entry.getValue().size() + "）")
                    .callbackData(navData(chatId, entry.getKey().key()))
                    .build()));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * 分类页键盘：每命令一行（{@code /命令 — 说明}），末行「← 返回」。
     *
     * <p>纵向而非两列：命令名长短不一（{@code words} vs {@code merchant_settle}），
     * 两列会让长名挤在一起；纵向也给说明留足横向空间（沿用原 {@code MenuCommandHandler} 的取舍）。
     */
    static InlineKeyboardMarkup categoryKeyboard(long chatId, List<String> commands,
                                                 Map<String, String> descriptions) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (String command : commands) {
            rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text(label(command, descriptions.get(command)))
                    .callbackData(runData(chatId, command))
                    .build()));
        }
        rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                .text(BACK_LABEL)
                .callbackData(navData(chatId, HOME_KEY))
                .build()));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * 按钮文案：{@code /命令 — 说明}；没写说明时退化为只有命令名（不因此让按钮变空壳）。
     *
     * <p>说明里的权限括注由 {@link CommandDescriptions#stripPermissionNote} 去掉——
     * 与客户端 {@code /} 菜单共用同一份实现。菜单**已经**按当前用户在此处的真实权限过滤过了，
     * 能看见这条按钮就说明他有权限，再写一遍既是噪声、又把按钮撑长。真正的权限语义仍由
     * {@code CommandDispatcher} 在执行时判定（菜单只是入口，不是门控）。
     */
    static String label(String command, String description) {
        String name = "/" + command;
        String clean = CommandDescriptions.stripPermissionNote(description);
        return clean.isEmpty() ? name : name + " — " + clean;
    }
}

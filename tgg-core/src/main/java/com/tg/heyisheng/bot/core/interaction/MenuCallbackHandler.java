package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.core.callback.CallbackHandler;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hub 按钮回调：解析 {@code menu:*}，按动词分派——**导航**就地换页、**执行**交给
 * {@link CallbackCommandBridge}。
 *
 * <p><b>本类不做命令门控</b>——权限、群开关、限流全在桥 → {@code CommandDispatcher} 那一侧。
 * 导航是纯展示：它只重算「此人此刻能看见什么」并重绘卡片，不执行任何命令，
 * 因此不会绕过确认卡（{@code nav:*} 根本不合成命令）。
 *
 * <p><b>导航为何就地编辑（{@code editMessageText}）而非发新消息</b>：菜单的本质是**同一张卡切换视图**，
 * 发新消息会让每次点击都堆一张卡（点四次分类＝聊天里五张卡）。命令**执行结果**仍照旧走新消息
 * ——那里要「解释命令返回值」，与导航不是一回事。消息 id 取不到时（异常来源）降级为发新消息，
 * 不因此报错。
 *
 * <p><b>为什么不在导航里拦「需要操作数的命令」</b>：各 handler 在 {@code commandArgs} 为空时
 * <b>自己就回用法文案</b>（如 {@code /delword} 无参回 {@code USAGE}）。让命令自己拥有用法文案，
 * 比在这里维护一份「哪些命令需要参数」的清单更不容易漂移——清单会随新命令悄悄过期。
 */
public class MenuCallbackHandler implements CallbackHandler {

    private static final String UNKNOWN = "未知操作。";

    private final CallbackCommandBridge bridge;
    private final MenuCatalog catalog;
    private final GroupConfigService groupConfigs;
    private final IdentityPresenter identity;

    public MenuCallbackHandler(CallbackCommandBridge bridge, MenuCatalog catalog,
                               GroupConfigService groupConfigs, IdentityPresenter identity) {
        this.bridge = bridge;
        this.catalog = catalog;
        this.groupConfigs = groupConfigs;
        this.identity = identity;
    }

    @Override
    public String action() {
        return MenuCommandHandler.CALLBACK_ACTION;
    }

    @Override
    public Optional<BotApiMethod<?>> handle(CallbackQuery query) {
        if (query == null) {
            return Optional.empty();
        }
        Parsed parsed = parse(query.getData());
        if (parsed == null) {
            // 必须应答：否则旧卡上的按钮会一直转圈，用户以为坏了（同 CallbackRouter 的约定）
            return Optional.of(answer(query, UNKNOWN));
        }
        if (parsed instanceof Parsed.Nav nav) {
            return Optional.of(navigate(query, nav));
        }
        Parsed.Run run = (Parsed.Run) parsed;
        // 执行路径**一字不变**：合成等价命令 → 桥 → CommandDispatcher（权限/群开关/确认卡照旧）
        return bridge.execute(query, run.chatId(), run.command(), null, false);
    }

    /**
     * 导航：重算可见性并重绘卡片。
     *
     * <p>每次导航都**重新判定**可见性（而非信任上一次的结果）——用户的权限或群开关可能在两次点击
     * 之间变了，卡片必须反映此刻的真实情况。这也让「点了别人转发的旧卡」得到正确结果。
     */
    private BotApiMethod<?> navigate(CallbackQuery query, Parsed.Nav nav) {
        Long userId = query.getFrom() == null ? null : query.getFrom().getId();
        long chatId = nav.chatId();
        if (userId == null) {
            return answer(query, UNKNOWN);
        }
        boolean groupEnabled = groupConfigs.findOrDefault(chatId).enabled();
        Map<MenuCategory, List<String>> grouped = catalog.grouped(chatId, userId, groupEnabled);

        Optional<MenuCategory> category = MenuCategory.fromKey(nav.key());
        List<String> commands = category.map(grouped::get).orElse(null);
        boolean showCategory = category.isPresent() && commands != null && !commands.isEmpty();

        if (showCategory) {
            return card(query, chatId, MenuView.categoryText(category.get()),
                    MenuView.categoryKeyboard(chatId, commands, catalog.descriptions()));
        }
        // 主页，或非法 key／该分类此刻已空 → 一律回主页（不发死按钮，也不猜）
        if (grouped.isEmpty()) {
            return card(query, chatId, MenuCommandHandler.NO_PERMISSION, null);
        }
        String identityLine = identity.menuIdentityLine(chatId, userId);
        return card(query, chatId, MenuView.homeText(identityLine), MenuView.homeKeyboard(chatId, grouped));
    }

    /**
     * 就地编辑原卡片；取不到消息 id 时降级为发新消息。
     *
     * <p>同时尽力而为地应答一次点击——产出走返回值（可靠），应答走主动通道（补充），
     * 与 {@link CallbackCommandBridge#execute} 的取舍一致。没有应答的路径下按钮可能转圈到超时，
     * 属已知行为（同桥的既有说明）。
     */
    private BotApiMethod<?> card(CallbackQuery query, long chatId, String text,
                                 InlineKeyboardMarkup keyboard) {
        bridge.acknowledge(query);
        Integer messageId = query.getMessage() == null ? null : query.getMessage().getMessageId();
        if (messageId != null) {
            return EditMessageText.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(messageId)
                    .text(text)
                    // keyboard 为 null 表示「这张卡不再可点」——必须显式传空键盘，
                    // 不传 replyMarkup 会**保留**原按钮，用户仍能点到已失效的入口
                    .replyMarkup(keyboard == null ? MenuView.noKeyboard() : keyboard)
                    .build();
        }
        return SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .replyMarkup(keyboard)
                .build();
    }

    /**
     * 解析回调 {@code data}；任何不合形即返回 {@code null}。
     *
     * <p>支持两种形状：
     * <ul>
     *   <li>新格式 4 段：{@code menu:<chatId>:nav:<key>} / {@code menu:<chatId>:run:<cmd>}；</li>
     *   <li>旧格式 3 段：{@code menu:<chatId>:<cmd>}（升级前发出的卡片仍在用户手上，视同 run）。</li>
     * </ul>
     *
     * <p>用 {@code split(":", -1)}：默认 {@code split(":")} 会丢尾部空串，
     * 于是 {@code menu:-100:}（命令名缺失）会被读成 2 段而非 3 段——本项目为此吃过一次亏，
     * 宁可显式保留空段再判空。
     */
    static Parsed parse(String data) {
        if (data == null) {
            return null;
        }
        String[] parts = data.split(":", -1);
        if (parts.length < 3 || !MenuCommandHandler.CALLBACK_ACTION.equals(parts[0])) {
            return null;
        }
        Long chatId;
        try {
            chatId = Long.valueOf(parts[1]);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (parts.length == 3) {
            return parts[2].isBlank() ? null : new Parsed.Run(chatId, parts[2]);
        }
        if (parts.length == 4) {
            String arg = parts[3];
            if (arg.isBlank()) {
                return null;
            }
            return switch (parts[2]) {
                case MenuView.NAV_VERB -> new Parsed.Nav(chatId, arg);
                case MenuView.RUN_VERB -> new Parsed.Run(chatId, arg);
                default -> null;
            };
        }
        return null;
    }

    private static AnswerCallbackQuery answer(CallbackQuery query, String text) {
        return AnswerCallbackQuery.builder()
                .callbackQueryId(query.getId())
                .text(text)
                .build();
    }

    /** 解析结果：导航（分类页 / 主页）或执行命令。 */
    sealed interface Parsed {

        /** 导航到某分类，或 {@code home} 回主页。 */
        record Nav(Long chatId, String key) implements Parsed {
        }

        /** 执行一条命令。 */
        record Run(Long chatId, String command) implements Parsed {
        }
    }
}

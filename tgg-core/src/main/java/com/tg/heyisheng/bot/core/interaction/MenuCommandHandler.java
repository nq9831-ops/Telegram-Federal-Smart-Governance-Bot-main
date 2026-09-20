package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /menu} —— 把「本群我还管得了什么」做成一张可点的卡。
 *
 * <p><b>存在的理由</b>：本项目的管理命令靠背——客户端菜单只是命令清单，不给参数、不给权限提示。
 * 本命令按<b>当前用户在当前群的真实权限</b>过滤出他能用的管理命令，点一下就走。
 *
 * <p><b>过滤规则（三条，缺一不可）</b>：
 * <ol>
 *   <li>{@code requiredPermission != NONE}——只收<b>管理类</b>。这条同时天然排除了
 *       「用全局白名单 guard 判定」的那批命令（复核/联邦/商家）：它们的注解权限是 {@code NONE}，
 *       从注册表<b>判不出</b>「此人是否可见」，故 v1 不列（v2 建可见性接缝，见设计文档 §7.4）。</li>
 *   <li>服务端再判一次权限——卡上的条目必须是此人真能执行的，不能只按注解有权限点就放上去。</li>
 *   <li>群开关：群已启用，或该命令声明了 {@code worksWhenDisabled}——保证 {@code /enable}
 *       在停用群里仍可见，否则该群会永久锁死（项目实测过的缺陷）。</li>
 * </ol>
 *
 * <p><b>权限不足时的行为是「回一句说明」</b>（用户 2026-09-20 拍板）：面板本身公开，且这正是
 * 「对管理员友好」的诉求；与命令层「权限不足即静默」的取舍不同——那里静默是为了不暴露命令存在，
 * 而这里用户点开的就是一张公开的面板。
 */
@BotCommand(value = "menu", description = "显示你可用的管理功能")
@Component
@ConditionalOnProperty(prefix = "tgg.interaction", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class MenuCommandHandler implements CommandHandler {

    static final String NO_PERMISSION = "你在此群没有管理权限。";
    static final String HEADER = "可用管理功能（只列出你在此群有权执行的）：\n"
            + "点按钮直接执行；需填参数的命令会提示用法。";
    /** 回调 data 的 action 前缀，需与 {@link MenuCallbackHandler#action()} 一致。 */
    static final String CALLBACK_ACTION = "menu";

    private final ObjectProvider<CommandRegistry> registryProvider;
    private final PermissionChecker permissionChecker;
    private final GroupConfigService groupConfigs;

    /**
     * @param registryProvider <b>必须是惰性句柄</b>——见下。
     *
     * <p><b>为什么收 {@code ObjectProvider} 而不直接收 {@code CommandRegistry}</b>：注册表正是由
     * 全部 {@code CommandHandler} bean（含本处理器）构造的：{@code commandRegistry ← List<CommandHandler>
     * ← menuCommandHandler ← CommandRegistry} —— 直接注入会构成**构造期循环依赖**，上下文直接起不来
     * （实测：{@code Requested bean is currently in creation: Is there an unresolvable circular reference?}）。
     * {@code ObjectProvider} 是 Spring 原生的惰性句柄，构造期不解析目标 bean，环因此断开；
     * 真正取用发生在 {@code /menu} 被调用的那一刻，此时注册表早已就绪。
     *
     * <p>由此得出一条应当记住的<b>结构约束</b>：{@code CommandHandler} 的实现不得（传递地）
     * 依赖 {@code CommandRegistry}——需要它时一律走惰性句柄。本项目此前没有任何处理器用过注册表，
     * 所以这条从未被踩到过。
     */
    public MenuCommandHandler(ObjectProvider<CommandRegistry> registryProvider,
                              PermissionChecker permissionChecker,
                              GroupConfigService groupConfigs) {
        this.registryProvider = registryProvider;
        this.permissionChecker = permissionChecker;
        this.groupConfigs = groupConfigs;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        CommandRegistry registry = registryProvider.getObject();
        Long chatId = ctx.chatId();
        Long userId = ctx.userId();
        boolean groupEnabled = groupConfigs.findOrDefault(chatId).enabled();

        List<String> visible = registry.mainCommands().keySet().stream()
                .filter(name -> !CALLBACK_ACTION.equals(name))
                .filter(name -> registry.requiredPermission(name) != Permission.NONE)
                .filter(name -> permissionChecker.has(chatId, userId, registry.requiredPermission(name)))
                .filter(name -> groupEnabled || registry.worksWhenDisabled(name))
                .toList();

        if (visible.isEmpty()) {
            return reply(chatId, NO_PERMISSION);
        }
        return SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(HEADER)
                .replyMarkup(keyboard(chatId, visible, registry.mainCommands()))
                .build();
    }

    /**
     * 每行一个按钮，文案 = {@code /命令 — 说明}。
     *
     * <p><b>为什么把说明放进按钮</b>：用户原话——「所有命令没有详细信息，交互不太友好」。
     * 只显示 {@code /words} 让人猜它的作用是主流 bot 菜单最常犯的毛病：同类项目的设计指南指出，
     * 「按钮文案含糊、无法预测下一步」直接导致用户流失。
     *
     * <p><b>纵向而非两列</b>：命令名长短不一（{@code words} vs {@code merchant_settle}），
     * 两列会让长名挤在一起；Telegram 每行最多 8 个按钮、整盘最多 100 个（**超出静默忽略**），
     * 纵向既不受列宽限制、也留足了说明的横向空间。
     *
     * <p><b>data 形如 {@code menu:<chatId>:<cmd>}</b>——chatId 放进 data 与
     * {@code VerificationCallbackHandler} 的约定一致（回调侧因此不必依赖可选的消息字段）。
     * 长度：5 + chatId(≤17) + 1 + cmd(≤32) ≤ 55 字节，稳在 Telegram 的 64 上限内——
     * 注意**超限不会报错，只是点击无响应**，所以这条必须靠断言守住，不能靠试。
     */
    static InlineKeyboardMarkup keyboard(Long chatId, List<String> commands,
                                         Map<String, String> descriptions) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (String command : commands) {
            InlineKeyboardButton button = InlineKeyboardButton.builder()
                    .text(label(command, descriptions.get(command)))
                    .callbackData(CALLBACK_ACTION + ":" + chatId + ":" + command)
                    .build();
            rows.add(new InlineKeyboardRow(button));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * 按钮文案：{@code /命令 — 说明}；没写说明时退化为只有命令名。
     *
     * <p>长度按同类项目经验控制在「十几个字」量级（过长的按钮文案会换行错乱、部分客户端直接截断）。
     */
    static String label(String command, String description) {
        String name = "/" + command;
        String clean = shortLabel(description);
        return clean.isEmpty() ? name : name + " — " + clean;
    }

    /**
     * 去掉说明结尾的括注（{@code （需管理员权限）} / {@code (平台复核人)} 之类）。
     *
     * <p><b>为什么可以去掉</b>：菜单**已经**按当前用户在本群的真实权限过滤过了——能看见这条按钮，
     * 就说明他有权限。再写一遍既是噪声，又把按钮撑长。真正的权限语义仍由
     * {@code CommandDispatcher} 在执行时判定（菜单只是入口，不是门控）。
     */
    static String shortLabel(String description) {
        if (description == null) {
            return "";
        }
        return description.replaceAll("[（(][^（()）]*[)）]\\s*$", "").trim();
    }

    private static SendMessage reply(Long chatId, String text) {
        return SendMessage.builder().chatId(String.valueOf(chatId)).text(text).build();
    }
}

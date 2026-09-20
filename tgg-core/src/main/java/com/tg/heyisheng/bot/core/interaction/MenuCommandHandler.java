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
    static final String HEADER = "可用管理功能（点击执行）：";
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
                .replyMarkup(keyboard(chatId, visible))
                .build();
    }

    /**
     * 每行一个按钮。
     *
     * <p>纵向而非两列是刻意的：命令名长短不一（{@code words} vs {@code merchant_settle}），
     * 两列会让长名挤在一起；且 Telegram 对每行按钮数有上限，纵向不受此约束。
     *
     * <p>data 形如 {@code menu:<chatId>:<cmd>}——<b>chatId 放进 data</b>与
     * {@code VerificationCallbackHandler} 的约定一致（回调侧因此不必依赖可选的消息字段）。
     * 长度：5 + chatId(≤17) + 1 + cmd(≤32) ≤ 55 字节，稳在 Telegram 的 64 上限内。
     */
    static InlineKeyboardMarkup keyboard(Long chatId, List<String> commands) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (String command : commands) {
            InlineKeyboardButton button = InlineKeyboardButton.builder()
                    .text("/" + command)
                    .callbackData(CALLBACK_ACTION + ":" + chatId + ":" + command)
                    .build();
            rows.add(new InlineKeyboardRow(button));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static SendMessage reply(Long chatId, String text) {
        return SendMessage.builder().chatId(String.valueOf(chatId)).text(text).build();
    }
}

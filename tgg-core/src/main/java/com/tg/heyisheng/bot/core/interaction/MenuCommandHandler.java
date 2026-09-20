package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;
import java.util.Map;

/**
 * {@code /menu} —— 把「本群我还管得了什么」做成一张**按业务域分组**的可点卡。
 *
 * <p><b>存在的理由</b>：本项目的管理命令靠背——客户端菜单只是命令清单，不给参数、不给权限提示。
 * 本命令按<b>当前用户在当前群的真实权限</b>过滤出他能用的管理命令，按分类呈现，点一下就走。
 *
 * <p><b>两级结构</b>（v2）：主页只列**非空的分类按钮**（内容审核 / 群设置 / 复核合规 / 收录商家），
 * 点进分类才展开该类命令（含「← 返回」）。在此之前是平铺一行一条——命令一多就成了一面墙。
 *
 * <p><b>可见性不再是本类的职责</b>：判定与分组收敛在 {@link MenuCatalog}，
 * 键盘渲染收敛在 {@link MenuView}。本类只负责「文本命令入口」这一件事——渲染主页。
 * 按钮回调走 {@link MenuCallbackHandler}，两者共用同一套渲染，避免漂移。
 *
 * <p><b>权限不足时的行为是「回一句说明」</b>（用户 2026-09-20 拍板）：面板本身公开，且这正是
 * 「对管理员友好」的诉求；与命令层「权限不足即静默」的取舍不同——那里静默是为了不暴露命令存在，
 * 而这里用户点开的就是一张公开的面板。
 */
@BotCommand(value = "menu", description = "显示你可用的管理功能", publicCommand = true)
@Component
@ConditionalOnProperty(prefix = "tgg.interaction", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class MenuCommandHandler implements CommandHandler {

    static final String NO_PERMISSION = "你在此群没有管理权限。";

    /** 回调 data 的 action 前缀，需与 {@link MenuCallbackHandler#action()} 一致。 */
    static final String CALLBACK_ACTION = "menu";

    private final MenuCatalog catalog;
    private final GroupConfigService groupConfigs;

    public MenuCommandHandler(MenuCatalog catalog, GroupConfigService groupConfigs) {
        this.catalog = catalog;
        this.groupConfigs = groupConfigs;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        Long chatId = ctx.chatId();
        Long userId = ctx.userId();
        boolean groupEnabled = groupConfigs.findOrDefault(chatId).enabled();

        Map<MenuCategory, List<String>> grouped = catalog.grouped(chatId, userId, groupEnabled);
        if (grouped.isEmpty()) {
            return reply(chatId, NO_PERMISSION);
        }
        return SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(MenuView.homeText())
                .replyMarkup(MenuView.homeKeyboard(chatId, grouped))
                .build();
    }

    private static SendMessage reply(Long chatId, String text) {
        return SendMessage.builder().chatId(String.valueOf(chatId)).text(text).build();
    }
}

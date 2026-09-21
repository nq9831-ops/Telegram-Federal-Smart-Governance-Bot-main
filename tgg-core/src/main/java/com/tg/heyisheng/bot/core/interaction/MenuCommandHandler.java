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
 * {@code /menu} —— 把「本群我还用得了什么」做成一张**按业务域分组**的可点卡。
 *
 * <p><b>存在的理由</b>：本项目原先靠客户端 {@code /} 菜单发现命令，但它只给清单、不给参数也不给
 * 权限提示；且现已**收敛为单一入口** {@code /menu}（其余命令不再出现在客户端菜单里）。
 * 本命令按<b>当前用户在当前群的真实权限</b>过滤出他能用的命令（含不需要权限的自助命令），
 * 按分类呈现，点一下就走。
 *
 * <p><b>两级结构</b>（v2）：主页只列**非空的分类按钮**（自助功能 / 内容审核 / 群设置 / 复核合规 /
 * 收录商家），点进分类才展开该类命令（含「← 返回」）。在此之前是平铺一行一条——命令一多就成了一面墙。
 *
 * <p><b>可见性不再是本类的职责</b>：判定与分组收敛在 {@link MenuCatalog}，
 * 键盘渲染收敛在 {@link MenuView}。本类只负责「文本命令入口」这一件事——渲染主页。
 * 按钮回调走 {@link MenuCallbackHandler}，两者共用同一套渲染，避免漂移。
 *
 * <p><b>权限不足时的行为是「回一句说明」</b>（用户 2026-09-20 拍板）：面板本身公开，且这正是
 * 「对管理员友好」的诉求；与命令层「权限不足即静默」的取舍不同——那里静默是为了不暴露命令存在，
 * 而这里用户点开的就是一张公开的面板。
 *
 * <p><b>{@code worksWhenDisabled = true 不可省略}</b>：客户端 {@code /} 菜单已收敛为**只留
 * {@code /menu} 一个入口**，面板是停用群管理员**唯一**能发现 {@code /enable} 的路径。若本命令在
 * 停用群被分发器拒绝，该群将永久锁死（只能改库恢复）——与 {@code /enable} 的 {@code worksWhenDisabled}
 * 同一条护栏。面板自身在停用群只列「恢复类」命令（见 {@link MenuCatalog}），不会因此泄露管理入口。
 */
@BotCommand(value = "menu", description = "显示你可用的功能", clientMenu = true,
        worksWhenDisabled = true)
@Component
@ConditionalOnProperty(prefix = "tgg.interaction", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class MenuCommandHandler implements CommandHandler {

    static final String NO_PERMISSION = "你在此群没有可用的功能。";

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

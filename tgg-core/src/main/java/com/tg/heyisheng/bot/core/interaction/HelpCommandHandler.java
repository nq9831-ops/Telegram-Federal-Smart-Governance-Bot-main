package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandDescriptions;
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
 * {@code /help} —— 按分类列出**你**在本群能用的功能（原文 §2.1）。
 *
 * <p><b>与 {@code /menu} 的分工</b>（不是重复）：{@code /menu} 是<b>点着用</b>的入口（按钮卡片）；
 * 本命令是<b>看一遍</b>的参考——纯文本、带每条命令的说明、可复制可搜索。
 * 按钮文案为放得下会截短，而这里每条命令一行完整说明。
 *
 * <p><b>可见性一字不改地复用 {@link MenuCatalog}</b>：按当前用户在本群的**真实权限**过滤
 * （含接缝判定的平台白名单类），再按 {@link MenuCategory} 分组。这是刻意的——
 * 若这里另写一套过滤，迟早漂移成「菜单看得见的 /help 看不见」（本项目已有「两处各写一份条件
 * 必然漂移」的教训）。同一条判据、同一处实现。
 *
 * <p><b>为什么与 {@code /menu} 同生共死</b>：本命令依赖 {@code MenuCatalog}，而后者由
 * {@code InteractionConfiguration} 在 {@code tgg.interaction.enabled} 下装配。交互关闭 =
 * 面板与帮助一并下线，这是运维的显式选择；不能让它以「缺 bean」的形式变成启动失败。
 *
 * <p><b>不泄露</b>：只列命令名与描述（都是公开可见的元数据），不含任何群配置内容。
 */
@BotCommand(value = "help", description = "按分类查看你能用的功能", publicCommand = true,
        category = MenuCategory.SELF_SERVICE)
@Component
@ConditionalOnProperty(prefix = "tgg.interaction", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class HelpCommandHandler implements CommandHandler {

    /** Telegram 单条消息硬上限（超长整条发送失败）。 */
    static final int TELEGRAM_TEXT_LIMIT = 4096;
    /** 为结尾提示预留的余量。 */
    private static final int TRUNCATION_RESERVE = 60;

    static final String EMPTY = "这个群里暂时没有你能用的功能。私聊我，我告诉你都有哪些。";
    static final String FOOTER = "\n\n点 /menu 可以直接点着用。";
    static final String TRUNCATED = "\n…（还有更多，用 /menu 面板查看）";

    private final MenuCatalog catalog;
    private final GroupConfigService groupConfigs;

    public HelpCommandHandler(MenuCatalog catalog, GroupConfigService groupConfigs) {
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
            return reply(chatId, EMPTY);
        }
        return reply(chatId, render(grouped, catalog.descriptions()));
    }

    /**
     * 渲染成文本：分类标题 + 每条命令一行（{@code /命令 —— 说明}）。
     *
     * <p>说明经 {@link CommandDescriptions#stripPermissionNote} 去权限括注——与 {@code /menu}
     * 按钮文案共用同一处清洗（菜单已按权限过滤过，能看见就有权限，再写一遍是噪声）。
     * 说明为空时退化为只有命令名，不让某一行为空。
     */
    static String render(Map<MenuCategory, List<String>> grouped, Map<String, String> descriptions) {
        int total = grouped.values().stream().mapToInt(List::size).sum();
        StringBuilder sb = new StringBuilder("你在本群能用的功能（共 ").append(total).append(" 条）：");
        for (Map.Entry<MenuCategory, List<String>> entry : grouped.entrySet()) {
            sb.append("\n\n【").append(entry.getKey().title()).append("】");
            for (String command : entry.getValue()) {
                String clean = CommandDescriptions.stripPermissionNote(descriptions.get(command));
                String line = "\n/" + command + (clean.isEmpty() ? "" : " —— " + clean);
                if (sb.length() + line.length() > TELEGRAM_TEXT_LIMIT - TRUNCATION_RESERVE) {
                    return sb.append(TRUNCATED).toString();
                }
                sb.append(line);
            }
        }
        return sb.append(FOOTER).toString();
    }

    private static SendMessage reply(Long chatId, String text) {
        return SendMessage.builder().chatId(String.valueOf(chatId)).text(text).build();
    }
}

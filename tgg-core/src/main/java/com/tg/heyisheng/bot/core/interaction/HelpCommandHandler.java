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
 * {@code /help} —— 按分类列出**你**在当前会话能用的功能（原文 §2.1）。
 *
 * <p><b>与 {@code /menu} 的分工</b>（不是重复）：{@code /menu} 是<b>点着用</b>的入口（按钮卡片）；
 * 本命令是<b>看一遍</b>的参考——纯文本、带每条命令的完整说明、可复制可搜索。
 *
 * <p><b>分头渲染（私聊 / 群聊，用户 2026-09-22 拍板）</b>：
 * <ul>
 *   <li><b>私聊版</b>：标题逐字「私聊可用的功能」；群限定命令（{@code @BotCommand(groupOnly)}，
 *       即 handler 内 chatId>=0 硬门那三条）不混进「可用」分组，单列「这些得到群里用」——
 *       判据是 {@link MenuCatalog#groupBoundCommands} 的存在量词（你在任一群有权用才列出）；</li>
 *   <li><b>群聊版</b>：维持现状结构，对群限定命令在行尾标注「（得在群里用）」，且说明
 *       <b>保留权限括注</b>——本命令承担「标注权限」的职责（/menu 按钮文案仍剥离，见
 *       {@code CommandDescriptions#stripPermissionNote}：按钮空间有限、能见即有权）。</li>
 * </ul>
 *
 * <p><b>可见性一字不改地复用 {@link MenuCatalog}</b>：按当前用户的真实权限过滤（含接缝判定的
 * 平台白名单类），再按 {@link MenuCategory} 分组。这是刻意的——若这里另写一套过滤，迟早漂移成
 * 「菜单看得见的 /help 看不见」（本项目已有「两处各写一份条件必然漂移」的教训）。
 * 同一条判据、同一处实现。
 *
 * <p><b>为什么与 {@code /menu} 同生共死</b>：本命令依赖 {@code MenuCatalog}，而后者由
 * {@code InteractionConfiguration} 在 {@code tgg.interaction.enabled} 下装配。交互关闭 =
 * 面板与帮助一并下线，这是运维的显式选择；不能让它以「缺 bean」的形式变成启动失败。
 *
 * <p><b>不泄露</b>：只列命令名与说明（都是公开可见的元数据），不含任何群配置内容。
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
        boolean privateChat = chatId != null && userId != null
                && IdentityPresenter.isPrivate(chatId, userId);

        Map<MenuCategory, List<String>> grouped = catalog.grouped(chatId, userId, groupEnabled);
        // 群限定命令的相关集：私聊版按存在量词进单列区；群聊版取「当前可见 ∩ 群限定」打行尾标注
        List<String> groupOnly = privateChat
                ? catalog.groupBoundCommands(userId)
                : grouped.values().stream().flatMap(List::stream).filter(catalog::isGroupOnly).toList();
        return reply(chatId, render(grouped, catalog.descriptions(), privateChat, groupOnly));
    }

    /**
     * 渲染成文本（分头：私聊 / 群聊）：分类标题 + 每条命令一行（{@code /命令 —— 说明}）。
     *
     * <p>说明<b>保留权限括注</b>（本命令承担「标注权限」的职责）；无说明时退化为只有命令名，
     * 不让某一行为空。私聊版把 {@code groupOnly} 单列「这些得到群里用」；群聊版对其中
     * 已可见的命令在行尾加「（得在群里用）」。
     *
     * <p>空态也在这里收口（便于零 mock 单测）：两个场景各回各的文案（{@link InteractionMessages}）。
     */
    static String render(Map<MenuCategory, List<String>> grouped, Map<String, String> descriptions,
                         boolean privateChat, List<String> groupOnly) {
        int total = grouped.values().stream().mapToInt(List::size).sum();
        List<String> groupBoundSection = privateChat ? groupOnly : List.of();
        if (total == 0 && groupBoundSection.isEmpty()) {
            return privateChat ? InteractionMessages.MENU_NO_PERMISSION_PRIVATE
                    : InteractionMessages.MENU_NO_PERMISSION;
        }

        StringBuilder sb = new StringBuilder();
        if (total > 0) {
            sb.append(InteractionMessages.helpTitle(total, privateChat));
        }
        for (Map.Entry<MenuCategory, List<String>> entry : grouped.entrySet()) {
            sb.append("\n\n【").append(entry.getKey().title()).append("】");
            for (String command : entry.getValue()) {
                String marker = (!privateChat && groupOnly.contains(command))
                        ? InteractionMessages.GROUP_ONLY_TAG : "";
                String line = "\n/" + command + describe(descriptions.get(command)) + marker;
                if (sb.length() + line.length() > TELEGRAM_TEXT_LIMIT - TRUNCATION_RESERVE) {
                    return sb.append(TRUNCATED).toString();
                }
                sb.append(line);
            }
        }
        if (!groupBoundSection.isEmpty()) {
            sb.append("\n\n").append(InteractionMessages.GROUP_BOUND_HEADER);
            for (String command : groupBoundSection) {
                String line = "\n/" + command + describe(descriptions.get(command));
                if (sb.length() + line.length() > TELEGRAM_TEXT_LIMIT - TRUNCATION_RESERVE) {
                    return sb.append(TRUNCATED).toString();
                }
                sb.append(line);
            }
        }
        return sb.append(FOOTER).toString();
    }

    /** 一行的说明部分：「 —— 说明」；无说明退化为空串（只显示命令名）。**不剥权限括注**。 */
    private static String describe(String description) {
        return description == null || description.isEmpty() ? "" : " —— " + description;
    }

    private static SendMessage reply(Long chatId, String text) {
        return SendMessage.builder().chatId(String.valueOf(chatId)).text(text).build();
    }
}

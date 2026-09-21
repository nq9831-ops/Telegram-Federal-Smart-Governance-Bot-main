package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.DispatchMessages;
import com.tg.heyisheng.bot.core.permission.Permission;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;
import java.util.Locale;

/**
 * {@code /group_tag <add|remove|list> [标签]} —— 管理<b>本群</b>话题标签（模块九 §10.5）。
 * 需 {@link Permission#MANAGE_CONFIG}。
 *
 * <p><b>权限载体与复核命令不同</b>：话题标签是<b>群内自治</b>动作（「我们群就是聊这个的」），
 * 故走群内 RBAC 的 {@code MANAGE_CONFIG}；而 {@code /review_*} 是<b>平台层</b>动作，走全局白名单
 * ——两者刻意用两套授权源（见 {@code ModerationReviewGuard} 的说明）。
 *
 * <p><b>声明标签后的效果</b>：本群内命中该话题的**敏感话题分级**被豁免（不再删除/入队）；
 * <b>红线不受影响</b>——标签不是免死金牌（§10.5）。
 *
 * <p>一个命令三个子动作（add/remove/list）：与 {@code /merchant_review <编号> approve|reject} 同款，
 * 避免为「列一下标签」再造一条命令。
 */
@BotCommand(value = "group_tag", description = "管理本群话题标签（需 MANAGE_CONFIG）",
        requiredPermission = Permission.MANAGE_CONFIG, category = MenuCategory.GROUP)
public class GroupTagCommandHandler implements CommandHandler {

    static final String USAGE = ModerationMessages.GROUPTAG_USAGE;
    static final String NOT_A_GROUP = DispatchMessages.GROUP_ONLY;

    private final GroupTopicTagService service;

    public GroupTagCommandHandler(GroupTopicTagService service) {
        this.service = service;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        Long chatId = ctx.chatId();
        if (chatId == null || chatId >= 0) {
            return reply(ctx, NOT_A_GROUP);
        }
        String args = ctx.commandArgs().orElse(null);
        if (args == null || args.isBlank()) {
            return reply(ctx, USAGE);
        }

        String[] parts = args.trim().split("\\s+", 2);
        String action = parts[0].toLowerCase(Locale.ROOT);
        String tag = parts.length > 1 ? parts[1].trim() : null;

        try {
            return switch (action) {
                case "add" -> reply(ctx, addReply(chatId, tag, ctx.userId()));
                case "remove", "rm", "del" -> reply(ctx, removeReply(chatId, tag));
                case "list" -> reply(ctx, listReply(chatId));
                default -> reply(ctx, USAGE);
            };
        } catch (TggException ex) {
            // 输入问题（空/超长/非法字符）：如实回显，不静默失败
            return reply(ctx, ModerationMessages.GROUPTAG_REJECTED_PREFIX + ex.getMessage());
        }
    }

    private String addReply(long chatId, String tag, Long actor) {
        boolean added = service.add(chatId, tag, actor);
        String clean = GroupTopicTagService.requireTag(tag);
        // 诚实回执：**只有已定义且可豁免的话题**才会真的产生豁免。此前对任意通过字符集校验的标签
        // 都回「豁免已生效」——弱代理为真、强谓词为假，运维会被误导（提交后审查抓到的 HIGH）。
        if (!SensitiveTopicDetector.isExemptableTag(clean)) {
            String why = SensitiveTopicDetector.knownTags().contains(clean)
                    ? ModerationMessages.GROUPTAG_NOT_EXEMPTABLE_KNOWN
                    : ModerationMessages.GROUPTAG_NOT_EXEMPTABLE_UNKNOWN;
            return ModerationMessages.groupTagRecordedNotExemptable(
                    clean, why, String.join(" / ", SensitiveTopicDetector.knownTags()));
        }
        return added
                ? ModerationMessages.groupTagDeclared(clean)
                : ModerationMessages.GROUPTAG_ALREADY_DECLARED;
    }

    private String removeReply(long chatId, String tag) {
        return service.remove(chatId, tag)
                ? ModerationMessages.groupTagRemoved(GroupTopicTagService.requireTag(tag))
                : ModerationMessages.GROUPTAG_REMOVE_MISSING;
    }

    private String listReply(long chatId) {
        List<GroupTopicTag> tags = service.listOf(chatId);
        if (tags.isEmpty()) {
            return ModerationMessages.GROUPTAG_LIST_EMPTY;
        }
        StringBuilder sb = new StringBuilder(ModerationMessages.GROUPTAG_LIST_HEAD_PREFIX
                + tags.size() + ModerationMessages.GROUPTAG_LIST_HEAD_SUFFIX);
        for (GroupTopicTag tag : tags) {
            sb.append("· ").append(tag.getTag()).append('\n');
        }
        sb.append(ModerationMessages.GROUPTAG_LIST_FOOTER);
        return sb.toString();
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

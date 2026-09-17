package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
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
        requiredPermission = Permission.MANAGE_CONFIG)
public class GroupTagCommandHandler implements CommandHandler {

    static final String USAGE = "用法：/group_tag add|remove|list [标签]\n"
            + "例：/group_tag add gambling —— 声明本群为赌博话题群，敏感话题分级对赌博豁免（红线不豁免）。";
    static final String NOT_A_GROUP = "请在要生效的群内执行本命令。";

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
            return reply(ctx, "标签未生效：" + ex.getMessage());
        }
    }

    private String addReply(long chatId, String tag, Long actor) {
        boolean added = service.add(chatId, tag, actor);
        String clean = GroupTopicTagService.requireTag(tag);
        return added
                ? "已声明本群话题标签：" + clean + "（该话题的敏感分级对本群豁免；红线不受影响）。"
                : "本群已声明该标签（无需重复）。";
    }

    private String removeReply(long chatId, String tag) {
        return service.remove(chatId, tag)
                ? "已移除本群话题标签：" + GroupTopicTagService.requireTag(tag) + "。"
                : "本群没有该标签。";
    }

    private String listReply(long chatId) {
        List<GroupTopicTag> tags = service.listOf(chatId);
        if (tags.isEmpty()) {
            return "本群未声明任何话题标签。";
        }
        StringBuilder sb = new StringBuilder("本群话题标签（" + tags.size() + "）：\n");
        for (GroupTopicTag tag : tags) {
            sb.append("· ").append(tag.getTag()).append('\n');
        }
        sb.append("（命中这些话题的敏感分级被豁免；红线不豁免。）");
        return sb.toString();
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

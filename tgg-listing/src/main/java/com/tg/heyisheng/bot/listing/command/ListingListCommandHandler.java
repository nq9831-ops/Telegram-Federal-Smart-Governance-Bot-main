package com.tg.heyisheng.bot.listing.command;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.listing.ListingGroup;
import com.tg.heyisheng.bot.listing.ListingGroupService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;

/**
 * {@code /listing_list} —— 列出本节点收录库中<b>有效</b>的群。任何成员可用。
 *
 * <p><b>必须截断，这是硬要求</b>：Bot API 单条消息上限 4096 字符，超长会<b>整条发送失败</b>
 * ——回复直接丢掉，调用方还以为命令成功了（本项目已知坑）。收录库随使用增长必然超过这个长度，
 * 所以截断不是「优化」，是「不发不出去」与「发得出去」的区别。
 *
 * <p><b>只列 ACTIVE</b>：被下架的条目已对外不可见，把 SUSPENDED 列出来等于公开「谁被下架了」，
 * 无论对群主还是对平台都不是该在群里公示的信息（审计侧另有按状态查询的入口）。
 *
 * <p><b>不回显邀请链接</b>：{@code https://t.me/+...} 的 token 等同入群凭证，
 * 在群内公示等于把收录库变成链接分发器。列表只给「编号 + 名称」，需要链接的人自己去找群。
 */
@BotCommand(value = "listing_list", description = "查看本节点收录库（有效群组）", publicCommand = true)
@ConditionalOnProperty(prefix = "tgg.listing", name = "enabled", havingValue = "true")
public class ListingListCommandHandler implements CommandHandler {

    /** Telegram 单条消息硬上限（超长整条失败）。 */
    static final int TELEGRAM_TEXT_LIMIT = 4096;
    /** 正文自留上限：给截断提示与后续可能的追加文本留出余量。 */
    static final int MAX_BODY_CHARS = 3500;

    static final String EMPTY = "收录库当前没有有效条目。";
    static final String PREFIX = "收录库（有效）：";
    static final String UNTITLED = "（未命名）";

    private final ListingGroupService service;

    public ListingListCommandHandler(ListingGroupService service) {
        this.service = service;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        return reply(ctx, render(service.activeGroups()));
    }

    /** 渲染列表：逐条追加，越过 {@link #MAX_BODY_CHARS} 就停手并说明被截断。 */
    static String render(List<ListingGroup> groups) {
        if (groups.isEmpty()) {
            return EMPTY;
        }
        StringBuilder sb = new StringBuilder(PREFIX);
        int shown = 0;
        for (ListingGroup group : groups) {
            String line = "\n#" + group.getId() + " " + titleOf(group);
            // 先判长度再追加：宁可少显示一条，也不产出超限的回复
            if (sb.length() + line.length() > MAX_BODY_CHARS) {
                break;
            }
            sb.append(line);
            shown++;
        }
        if (shown < groups.size()) {
            sb.append("\n…（已截断：共 ").append(groups.size())
                    .append(" 条，本条只显示前 ").append(shown).append(" 条）");
        }
        return sb.toString();
    }

    private static String titleOf(ListingGroup group) {
        String title = group.getTitle();
        return (title == null || title.isBlank()) ? UNTITLED : title;
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

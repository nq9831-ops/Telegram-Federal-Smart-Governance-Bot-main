package com.tg.heyisheng.bot.listing.command;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.listing.ListingGroupService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * {@code /listing_add <邀请链接>} —— 把<b>当前群</b>加入收录库。需 {@link Permission#MANAGE_CONFIG}。
 *
 * <p><b>为什么需要它</b>：V5.0 的模块五只写了「定期验证」，没写收录条目从哪来；没有写入入口，
 * 收录库永远是空的，「验证」也就无从跑起（设计文档 §6.3 / §11 决策 2：做这个入口）。
 *
 * <p><b>为什么 chatId 取 {@link UpdateContext#chatId()} 而不是从链接解析</b>：命令在目标群内执行，
 * 群身份当场就有；而从邀请链接反查 chatId 需要一次真实的 Bot API 调用（本机不可用，
 * 且失败时会把「没查到」误当「群不存在」）。命令在群内执行这一约束由 {@link #NOT_A_GROUP} 兜住。
 *
 * <p><b>命令名是 {@code listing_add} 而非设计文档写的 {@code /listing add}</b>：命令注册表
 * （{@code CommandRegistry}）是「一个命令名 → 一个处理器 + 一个权限点」的严格映射，
 * 且命令名由库的 {@code Message.getCommand()} 取自 Telegram 的 {@code bot_command} 实体
 * （只覆盖第一个词，{@code /listing add x} 只会得到 {@code /listing}）。三条命令的权限各不相同
 * （add 需 {@code MANAGE_CONFIG}、list 人人可用、appeal 限提交者），因此必须是三个<b>互异</b>的
 * 命令名——下划线形式保留了设计文档的两段式语义，且完全不依赖文本切分。
 *
 * <p><b>幂等</b>：重复提交同一个群走数据库唯一键 + {@code INSERT IGNORE} 静默吸收，
 * 不抛异常、不覆盖既有条目（见 {@code ListingGroupService#submit}）。
 */
@BotCommand(value = "listing_add", description = "把本群加入收录库（需管理员权限）",
        requiredPermission = Permission.MANAGE_CONFIG, category = MenuCategory.LISTING)
@ConditionalOnProperty(prefix = "tgg.listing", name = "enabled", havingValue = "true")
public class ListingAddCommandHandler implements CommandHandler {

    static final String USAGE = "用法：/listing_add <邀请链接>";
    static final String NOT_A_GROUP = "请在要收录的群内执行本命令。";
    static final String INVALID_LINK = "邀请链接无效：应以 https://t.me/ 开头。";
    static final String ADDED = "已提交收录，将定期验证链接有效性。";
    static final String ALREADY_EXISTS = "该群已在收录库中（重复提交已忽略）。";

    /** 邀请链接的可接受前缀（Telegram 邀请链接恒为 t.me 域）。 */
    static final String LINK_PREFIX = "https://t.me/";

    private final ListingGroupService service;

    public ListingAddCommandHandler(ListingGroupService service) {
        this.service = service;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        String inviteLink = ctx.commandArgs().orElse(null);
        if (inviteLink == null) {
            return reply(ctx, USAGE);
        }
        Long chatId = ctx.chatId();
        // 收录对象是「群」：群/超级群 id 为负数。私聊里执行会把用户 id 当成群 id 存进去——
        // 那是脏数据，且后续验证必然失败，故此处直接拒绝。
        if (chatId == null || chatId >= 0) {
            return reply(ctx, NOT_A_GROUP);
        }
        if (!inviteLink.startsWith(LINK_PREFIX)) {
            return reply(ctx, INVALID_LINK);
        }

        // title 暂缺（UpdateContext 只带路由元数据，不含群标题）；留空不影响收录与验证。
        boolean created = service.submit(chatId, inviteLink, null, ctx.userId());
        return reply(ctx, created ? ADDED : ALREADY_EXISTS);
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

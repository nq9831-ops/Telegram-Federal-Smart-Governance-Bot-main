package com.tg.heyisheng.bot.listing.command;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.listing.merchant.Merchant;
import com.tg.heyisheng.bot.listing.merchant.MerchantService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.Optional;

/**
 * {@code /merchant_apply <商家名称>} —— 提交商家入驻申请（设计文档 §6.1）。任何成员可用。
 *
 * <p><b>为什么参数只收名称</b>：V5.0 的「基础信息 → 资质提交」是多轮对话；本阶段用一条命令
 * 收最小必填字段（{@code merchants.name} 是唯一 NOT NULL 的业务列），其余（类别 / 简介 / 联系方式）
 * 留空即可入库，后续可由模块十一（Web 后台）或增补命令补齐。多轮对话状态机在 Bot 命令框架里
 * 需要额外的会话状态存储，本阶段不引入——把「能端到端验证」放在「字段齐全」之前。
 *
 * <p><b>命令名是 {@code merchant_apply} 而非设计文档写的 {@code /merchant apply}</b>：命令注册表
 * 是「一名一处理器一权限点」的严格映射，且命令名取自 Telegram 的 {@code bot_command} 实体
 * （只覆盖第一个词），故三条命令必须是三个<b>互异</b>命令名——理由同
 * {@code ListingAddCommandHandler} 的 javadoc。
 *
 * <p><b>防重复提交</b>：同一用户已有「在办」申请时拒绝新建并回显既有编号
 * （否则连续点两次就会产出两条并行申请，复核人无从判断该审哪条）。
 */
@BotCommand(value = "merchant_apply", description = "提交商家入驻申请", publicCommand = true,
        category = MenuCategory.LISTING)
@ConditionalOnProperty(prefix = "tgg.merchant", name = "enabled", havingValue = "true")
public class MerchantApplyCommandHandler implements CommandHandler {

    /** 用法文案刻意<b>不含 {@code <...>}</b>：实测会被连尖括号一起照抄，命令因而一直回用法。 */
    static final String USAGE = "用法：/merchant_apply 商家名称\n例：/merchant_apply 测试小铺";
    static final String NO_IDENTITY = "无法识别你的用户身份，请稍后再试。";
    static final String NAME_TOO_LONG = "商家名称过长（上限 255 字符）。";

    /** {@code merchants.name} 列宽上限。 */
    static final int MAX_NAME_CHARS = 255;

    private final MerchantService service;

    public MerchantApplyCommandHandler(MerchantService service) {
        this.service = service;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        String rawName = ctx.commandArgs().orElse(null);
        if (rawName == null || rawName.isBlank()) {
            return reply(ctx, USAGE);
        }
        String name = rawName.trim();
        if (name.length() > MAX_NAME_CHARS) {
            return reply(ctx, NAME_TOO_LONG);
        }
        Long userId = ctx.userId();
        if (userId == null) {
            return reply(ctx, NO_IDENTITY);
        }

        Optional<Merchant> open = service.findOpenByOwner(userId);
        if (open.isPresent()) {
            return reply(ctx, "你已有在办的入驻申请（编号 #" + open.get().getId() + "），请等待复核。");
        }

        Merchant saved = service.submit(userId, name, null, null, null);
        return reply(ctx, "入驻申请已提交（编号 #" + saved.getId() + "），等待资质复核。");
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

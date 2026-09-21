package com.tg.heyisheng.bot.listing.command;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.listing.merchant.Merchant;
import com.tg.heyisheng.bot.listing.merchant.MerchantService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.Optional;

/**
 * {@code /merchant_status} —— 查看<b>本人</b>商家的入驻状态（设计文档 §6.1）。
 *
 * <p><b>只回本人最新一条</b>：不列出他人商家（无权限门槛的命令若泄露全量名录，
 * 等于把商家库开放给任何成员），也不列出本人的历史条目（用户关心的是「现在到哪一步了」）。
 *
 * <p><b>状态用中文短句而非枚举名</b>：{@code DEPOSIT_PENDING} 之类的内部枚举名对用户没有意义，
 * 而「待缴纳保证金」直接告诉他下一步要做什么。
 */
@BotCommand(value = "merchant_status", description = "查看我的商家入驻状态", publicCommand = true)
@ConditionalOnProperty(prefix = "tgg.merchant", name = "enabled", havingValue = "true")
public class MerchantStatusCommandHandler implements CommandHandler {

    static final String NO_APPLICATION = "你还没有提交过商家入驻申请。\n用法：/merchant_apply 商家名称（例：/merchant_apply 测试小铺）";

    private final MerchantService service;

    public MerchantStatusCommandHandler(MerchantService service) {
        this.service = service;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        Long userId = ctx.userId();
        if (userId == null) {
            return null;
        }
        Optional<Merchant> latest = service.latestByOwner(userId);
        if (latest.isEmpty()) {
            return reply(ctx, NO_APPLICATION);
        }
        Merchant merchant = latest.get();
        return reply(ctx, "商家入驻状态：\n编号 #" + merchant.getId() + " · " + merchant.getName()
                + "\n状态：" + describe(merchant.getStatus())
                + (merchant.getTier() == null ? "" : "\n等级：" + merchant.getTier()));
    }

    /** 状态枚举 → 对用户可读的中文短句。 */
    static String describe(String status) {
        return switch (status) {
            case "SUBMITTED" -> "已提交，等待资质复核";
            case "UNDER_REVIEW" -> "资质复核中";
            case "APPROVED" -> "资质已通过，等待缴纳保证金";
            case "NEED_MORE" -> "需补充材料，请修改后重新提交";
            case "REJECTED" -> "资质未通过";
            case "DEPOSIT_PENDING" -> "待缴纳保证金";
            case "ACTIVE" -> "入驻成功（营业中）";
            default -> status;
        };
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

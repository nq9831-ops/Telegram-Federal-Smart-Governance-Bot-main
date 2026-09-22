package com.tg.heyisheng.bot.listing.command;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.Confirm;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.listing.merchant.Merchant;
import com.tg.heyisheng.bot.listing.merchant.MerchantDeposit;
import com.tg.heyisheng.bot.listing.merchant.MerchantDepositService;
import com.tg.heyisheng.bot.listing.merchant.MerchantService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.Optional;

/**
 * {@code /merchant_exit <商家编号>} —— 商家申请退出（设计文档 §6.1）。仅<b>商家本人</b>。
 *
 * <p><b>退出为什么先冻结而不是直接退还</b>：退出时可能存在未结争议或未履行赔付义务，
 * 直接退款会让平台失去追偿手段。冻结把「钱还动不动」这个决定推迟到争议核查之后
 * （{@code MerchantDepositService.settle} 的三分支），期间资金既不给商家也不归平台。
 *
 * <p><b>只做「冻结」这一半</b>：结算需要「未结争议 / 赔付金额」这类输入，而争议模型本阶段尚未落地
 * （设计文档 §10 已把「异议期到点后的自动动作」列为后续）。故本命令只把保证金推进到 FROZEN，
 * 后续结算由管理员经服务入口完成——比在一个命令里猜一个争议结论要好。
 *
 * <p><b>身份校验在 handler 内</b>：{@code /merchant_exit} 是「无权限门槛但限本人」的命令
 * （同 {@code /listing_appeal}）——{@code @BotCommand.requiredPermission} 表达不了
 * 「资源的属主」，故不声明权限点，由本类比对 {@code ownerUserId}。
 */
@BotCommand(value = "merchant_exit", description = "申请退出商家（触发保证金冻结）",
        confirm = Confirm.ALWAYS, publicCommand = true, category = MenuCategory.MERCHANT)
@ConditionalOnProperty(prefix = "tgg.merchant", name = "enabled", havingValue = "true")
public class MerchantExitCommandHandler implements CommandHandler {

    static final String USAGE = ListingMessages.MERCHANT_EXIT_USAGE;
    static final String NOT_OWNER = ListingMessages.MERCHANT_NOT_OWNER;

    private final MerchantService merchants;
    private final MerchantDepositService depositService;

    public MerchantExitCommandHandler(MerchantService merchants, MerchantDepositService depositService) {
        this.merchants = merchants;
        this.depositService = depositService;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        Long merchantId = parseId(ctx.commandArgs().orElse(null));
        if (merchantId == null) {
            return reply(ctx, USAGE);
        }
        Optional<Merchant> found = merchants.find(merchantId);
        if (found.isEmpty()) {
            return reply(ctx, ListingMessages.merchantNotFound(merchantId));
        }
        if (ctx.userId() == null || !ctx.userId().equals(found.get().getOwnerUserId())) {
            return reply(ctx, NOT_OWNER);
        }

        Optional<MerchantDeposit> frozen =
                depositService.freeze(merchantId, "商家申请退出", ctx.userId());
        if (frozen.isEmpty()) {
            return reply(ctx, ListingMessages.EXIT_NO_DEPOSIT);
        }
        return reply(ctx, ListingMessages.exitAccepted(merchantId));
    }

    private static Long parseId(String args) {
        if (args == null || args.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(args.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

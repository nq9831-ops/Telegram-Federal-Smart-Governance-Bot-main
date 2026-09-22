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
 * <p><b>「退出不改商家状态」是有意契约，不是漏改</b>（保守路线，产品已定，不引入行为变更）：
 * 本命令<b>只</b>经 {@code MerchantDepositService.freeze} 把保证金推到 {@code FROZEN}，
 * <b>绝不触碰</b> {@code merchants.status}——退出后 {@link Merchant.Status} 仍是 {@code ACTIVE}。
 * 原因有二：① {@code Merchant.Status} 目前<b>没有退出态</b>（退出态一旦引入，会牵动
 * {@code MerchantService.OPEN_STATUSES} 的「在办」判定、信用分/等级、以及既有查询的语义，
 * 属于行为变更，超出本轮保守范围）；② 「钱已冻结」已由保证金状态机表达，商家状态的语义
 * 仍是「入驻是否获批」，两者职责不同——用后者承载「已申请退出」会把两种事实耦合在一起。
 * 现状由守门测试 {@code MerchantExitCommandHandlerTest#exitFreezesDepositOnlyAndIntentionallyLeavesStatusActive}
 * 钉住。
 *
 * <p><b>复访条件（未来真要引入退出态时需要什么）</b>：
 * <ol>
 *   <li>产品决定一次退出语义变更——即在 {@code Merchant.Status} 新增 {@code EXITED}（或同类）值，
 *       并明确它与 {@code ACTIVE} 的关系（是否可从 {@code ACTIVE} 迁移、是否可逆/复活）；</li>
 *   <li>同步决定 {@code OPEN_STATUSES} 是否纳入该态、以及退出后信用分/等级如何处置
 *       （保留、冻结还是归档）——这些都要先有结论，不能在 handler 里顺手实现；</li>
 *   <li>届时本命令改为在冻结之后显式推进该状态，并把守门测试从「钉住不改」翻转为
 *       「钉住改为 EXITED」，同时更新 {@code docs/KNOWN-ISSUES.md} 第 18 条。</li>
 * </ol>
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

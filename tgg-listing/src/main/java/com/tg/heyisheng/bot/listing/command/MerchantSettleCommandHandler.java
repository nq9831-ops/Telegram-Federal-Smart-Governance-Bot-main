package com.tg.heyisheng.bot.listing.command;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.Confirm;
import com.tg.heyisheng.bot.listing.merchant.MerchantDeposit;
import com.tg.heyisheng.bot.listing.merchant.MerchantDepositService;
import com.tg.heyisheng.bot.listing.merchant.MerchantReviewGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /merchant_settle <商家编号> <NONE|UNRESOLVED|WITH_COMPENSATION> [扣款额] [理由]}
 * —— 平台侧结算已冻结的商家保证金。
 *
 * <p><b>为什么必须有它</b>：{@code /merchant_exit} 把保证金推到 {@code FROZEN} 之后，
 * {@link MerchantDepositService#settle} 此前<b>只有测试调用方</b>（{@code src/main} 零调用）——
 * 即「退出申请被受理了，钱冻在那里，却没有任何入口能把它结算掉」。本命令补上出口，
 * 与 {@code /merchant_deposit} 补上「缴纳」是同一类修复（写好了但接不上）。
 *
 * <p><b>三个分支的语义</b>（判定在 {@link MerchantDepositService}，本命令只负责送达参数并复述结果）：
 * <ul>
 *   <li>{@code NONE} —— 无争议，全额退还；</li>
 *   <li>{@code UNRESOLVED} —— 有未结争议，<b>保持冻结</b>（不动状态、不写流水），结案后再执行一次；</li>
 *   <li>{@code WITH_COMPENSATION} —— 扣赔付后退还余额；<b>扣款额与理由都必填</b>
 *       （V5.0「扣除必带理由」，命令层先挡一道，service 再挡一道）。</li>
 * </ul>
 *
 * <p><b>为什么不做成争议模型</b>：「有无争议、赔付多少」当前由运营者人工判断。
 * 建完整的争议表 + 举证 + 裁决流程是模块十二（担保交易）的一部分——本阶段没有对手方，
 * 做了也无法验证（链上不可达）。故这里走「最小可用」：把死胡同打通，不越界造一个空壳系统。
 *
 * <p><b>权限：平台层复核人白名单</b>（{@link MerchantReviewGuard}，配置 {@code tgg.merchant.reviewers}），
 * 与 {@code /merchant_review}·{@code /merchant_deposit} 同源——结算涉及资金，属平台侧动作，
 * 而 {@code Role} 是群内语义。非复核人返回 {@code null}（静默），与 {@code CommandDispatcher}
 * 的「权限不足即静默」一致。
 */
@BotCommand(value = "merchant_settle", description = "结算已冻结的商家保证金（平台复核人）",
        confirm = Confirm.ALWAYS, category = MenuCategory.LISTING)
@ConditionalOnProperty(prefix = "tgg.merchant", name = "enabled", havingValue = "true")
public class MerchantSettleCommandHandler implements CommandHandler {

    static final String USAGE = ListingMessages.MERCHANT_SETTLE_USAGE;

    private final MerchantDepositService depositService;
    private final MerchantReviewGuard guard;

    public MerchantSettleCommandHandler(MerchantDepositService depositService, MerchantReviewGuard guard) {
        this.depositService = depositService;
        this.guard = guard;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        if (!guard.isReviewer(ctx.userId())) {
            return null;
        }

        Parsed parsed = Parsed.of(ctx.commandArgs().orElse(null));
        if (parsed == null) {
            return reply(ctx, USAGE);
        }

        if (depositService.find(parsed.merchantId()).isEmpty()) {
            return reply(ctx, ListingMessages.depositNotFound(parsed.merchantId()));
        }

        try {
            Optional<MerchantDeposit> after = depositService.settle(parsed.merchantId(), parsed.dispute(),
                    parsed.deduction(), parsed.reason(), ctx.userId());
            // 已在上面确认存在，故这里实际必非空；仍显式兜住，避免「find 与 settle 之间记录被删」时 NPE。
            return after.map(deposit -> reply(ctx, describe(parsed, deposit)))
                    .orElseGet(() -> reply(ctx,
                            ListingMessages.depositNotFound(parsed.merchantId())));
        } catch (TggException ex) {
            // 业务守卫（非 FROZEN 结算 / 扣除超限 / 扣除无理由）必须让运营者看见原因，不得吞成静默。
            return reply(ctx, ListingMessages.SETTLE_FAILED_PREFIX + ex.getMessage());
        }
    }

    /** 复述本次结算做了什么。终态取自 service 返回的实体（状态机归 service，此处只如实转述）。 */
    private static String describe(Parsed parsed, MerchantDeposit deposit) {
        String amount = deposit.getAmount().toPlainString();
        return switch (parsed.dispute()) {
            case NONE -> ListingMessages.settledRefunded(
                    parsed.merchantId(), amount, deposit.getState());
            case UNRESOLVED -> ListingMessages.settledUnresolved(parsed.merchantId(), amount);
            case WITH_COMPENSATION -> ListingMessages.settledWithCompensation(
                    parsed.deduction().toPlainString(), parsed.reason(), parsed.merchantId(),
                    deposit.getState());
        };
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }

    /**
     * 命令操作数：商家编号 + 分支 + （仅 {@code WITH_COMPENSATION}）扣款额与理由。
     *
     * <p>非法一律返回 {@code null}（回用法说明），<b>不把脏参数递进 service 再靠异常兜</b>——
     * 命令层与领域层的校验职责分开，报错才指向正确的地方。
     *
     * <p>NONE / UNRESOLVED <b>不吃</b>扣款额与理由（尾部多余文本忽略，不报错——
     * 免得运营者被格式细节绊住）；理由作为<b>尾部剩余文本</b>解析，允许含空格
     * （对齐 {@code requireText} 与 {@code requireToken} 的契约区分，见交接文档坑 33）。
     */
    private record Parsed(long merchantId, MerchantDepositService.Dispute dispute,
                          BigDecimal deduction, String reason) {

        static Parsed of(String args) {
            if (args == null || args.isBlank()) {
                return null;
            }
            String[] parts = args.trim().split("\\s+", 3);
            if (parts.length < 2) {
                return null;
            }

            long merchantId;
            try {
                merchantId = Long.parseLong(parts[0]);
            } catch (NumberFormatException ex) {
                return null;
            }

            MerchantDepositService.Dispute dispute;
            try {
                dispute = MerchantDepositService.Dispute.valueOf(parts[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return null;
            }

            if (dispute != MerchantDepositService.Dispute.WITH_COMPENSATION) {
                return new Parsed(merchantId, dispute, null, null);
            }

            if (parts.length < 3) {
                return null;
            }
            String[] tail = parts[2].trim().split("\\s+", 2);
            if (tail.length < 2 || tail[1].isBlank()) {
                return null;
            }
            BigDecimal deduction;
            try {
                deduction = new BigDecimal(tail[0]);
            } catch (NumberFormatException ex) {
                return null;
            }
            if (deduction.signum() <= 0) {
                return null;
            }
            return new Parsed(merchantId, dispute, deduction, tail[1].trim());
        }
    }
}

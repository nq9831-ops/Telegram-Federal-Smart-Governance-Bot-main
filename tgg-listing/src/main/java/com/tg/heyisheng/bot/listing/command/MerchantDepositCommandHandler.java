package com.tg.heyisheng.bot.listing.command;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.listing.merchant.Merchant;
import com.tg.heyisheng.bot.listing.merchant.MerchantDeposit;
import com.tg.heyisheng.bot.listing.merchant.MerchantDepositService;
import com.tg.heyisheng.bot.listing.merchant.MerchantReviewGuard;
import com.tg.heyisheng.bot.listing.merchant.MerchantService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * {@code /merchant_deposit <商家编号> <金额>} —— 平台侧确认商家保证金并完成入驻。
 *
 * <p><b>为什么必须有它</b>：V5.0 与设计文档 §6.1 的命令表都**没有定义缴纳保证金这一步**，
 * 于是 `MerchantDepositService.open` / `lock` 写好了却没有任何生产调用方——
 * 商家经命令链路最多停在 {@code APPROVED}，**永远到不了 {@code ACTIVE}**
 * （连带信用分初始化与等级评定也不可达）。这是「写好了但接不上」的典型，本命令补上这一环。
 *
 * <p><b>语义：一步到位的「确认」而不是「转账」</b>。真实世界里保证金是链上转账，
 * 平台只能在收到后确认；本阶段 {@code DepositGateway} 是 noop，所以「确认」就是全部输入。
 * 命令内部完成 {@code open}（建账 + 推到 {@code DEPOSIT_PENDING}）与 {@code lock}
 * （锁仓 + 推到 {@code ACTIVE} + 信用分初始化 + 等级评定）两步——
 * 拆成两条命令只会让运维在中间态里犯错。
 *
 * <p><b>权限：平台层复核人白名单</b>（{@link MerchantReviewGuard}，配置 {@code tgg.merchant.reviewers}），
 * 理由同 {@code /merchant_review}——保证金是平台侧动作，{@code Role} 是群内语义。
 * 本阶段复核人兼保证金操作人；将来若需分离职能，再拆一个白名单即可（判定点已收敛在 guard）。
 *
 * <p><b>非复核人返回 {@code null}（静默）</b>：与 {@code CommandDispatcher} 的「权限不足即静默」一致。
 *
 * <p><b>幂等</b>：已有保证金记录的商家直接给出提示，不重复开通（否则第二次会撞 `lock` 的 PENDING 守卫）。
 */
@BotCommand(value = "merchant_deposit", description = "确认商家保证金并完成入驻（平台复核人）")
@ConditionalOnProperty(prefix = "tgg.merchant", name = "enabled", havingValue = "true")
public class MerchantDepositCommandHandler implements CommandHandler {

    static final String USAGE = "用法：/merchant_deposit <商家编号> <金额>";

    private final MerchantService merchants;
    private final MerchantDepositService depositService;
    private final MerchantReviewGuard guard;

    public MerchantDepositCommandHandler(MerchantService merchants,
                                         MerchantDepositService depositService,
                                         MerchantReviewGuard guard) {
        this.merchants = merchants;
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

        Optional<Merchant> found = merchants.find(parsed.merchantId());
        if (found.isEmpty()) {
            return reply(ctx, "未找到商家编号 " + parsed.merchantId() + "。");
        }

        Optional<MerchantDeposit> existing = depositService.find(parsed.merchantId());
        if (existing.isPresent()) {
            return reply(ctx, "该商家保证金已存在（当前 " + existing.get().getState() + "），无需重复操作。");
        }

        Merchant.Status status = Merchant.parse(found.get().getStatus());
        if (status != Merchant.Status.APPROVED) {
            return reply(ctx, "该商家当前状态为 " + status + "，不可缴纳保证金（需先复核通过）。");
        }

        depositService.open(parsed.merchantId(), parsed.amount(), null);
        depositService.lock(parsed.merchantId());
        return reply(ctx, "保证金已确认（商家 #" + parsed.merchantId() + "，"
                + parsed.amount().toPlainString() + " USDT），入驻完成。");
    }

    /** 命令操作数：目标商家编号 + 保证金金额。解析失败（缺参/非数字/非正数）返回 {@code null}。 */
    private record Parsed(long merchantId, BigDecimal amount) {

        static Parsed of(String args) {
            if (args == null || args.isBlank()) {
                return null;
            }
            String[] parts = args.trim().split("\\s+", 2);
            if (parts.length < 2) {
                return null;
            }
            long merchantId;
            try {
                merchantId = Long.parseLong(parts[0]);
            } catch (NumberFormatException ex) {
                return null;
            }
            BigDecimal amount;
            try {
                amount = new BigDecimal(parts[1].trim());
            } catch (NumberFormatException ex) {
                return null;
            }
            return amount.signum() <= 0 ? null : new Parsed(merchantId, amount);
        }
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

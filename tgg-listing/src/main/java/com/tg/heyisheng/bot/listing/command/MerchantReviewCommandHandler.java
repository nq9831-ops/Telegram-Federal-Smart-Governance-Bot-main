package com.tg.heyisheng.bot.listing.command;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.platform.FederationAdminGuard;
import com.tg.heyisheng.bot.listing.merchant.Merchant;
import com.tg.heyisheng.bot.listing.merchant.MerchantService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.Locale;
import java.util.Optional;

/**
 * {@code /merchant_review <商家编号> approve|reject|need-more} —— 商家资质人工复核（设计文档 §6.1）。
 *
 * <p><b>权限载体是全局白名单，不是 {@code @BotCommand.requiredPermission}</b>：
 * 商家只有<b>联邦管理员</b>才可以审核处理（用户 2026-09-22 二次拍板）——授权源是
 * {@code tgg.federation.admins} / 平台 {@code FEDERATION_ADMIN}（{@link FederationAdminGuard}），
 * 旧商家复核通道（{@code tgg.merchant.reviewers}）已裁撤。资质复核是<b>平台层</b>动作，
 * 而 {@code Permission}/{@code Role} 是群内权能模型。故本命令不声明权限点，门控在 handler 内完成。
 *
 * <p><b>非联邦管理员返回 {@code null}（静默）</b>：与 {@code CommandDispatcher} 的「权限不足即静默」
 * 语义一致——回复「权限不足」等于向无权者确认了命令存在。
 *
 * <p><b>状态机两条通道</b>：{@code SUBMITTED}/{@code NEED_MORE} 先经
 * {@link MerchantService#beginReview} 进入 {@code UNDER_REVIEW}，再由
 * {@link MerchantService#decide} 写入结论；已是 {@code UNDER_REVIEW} 的直接落结论。
 * 其余状态（{@code APPROVED}/{@code DEPOSIT_PENDING}/{@code ACTIVE}/{@code REJECTED}）
 * 不可复核——给出明确提示，而不是让实体的非法迁移异常穿透到分发层。
 */
@BotCommand(value = "merchant_review", description = "商家资质复核（复核人）",
        category = MenuCategory.MERCHANT)
@ConditionalOnProperty(prefix = "tgg.merchant", name = "enabled", havingValue = "true")
public class MerchantReviewCommandHandler implements CommandHandler {

    /**
     * 用法文案刻意<b>不含 {@code <...>} 与 {@code |}</b>：实测有运营者把模板原样发出去
     * （把 {@code approve|reject|need-more} 整串当成参数），命令因此一直回用法，看起来像坏了。
     * 给「可照抄的示例」而不是「参数模板」——文案本身要经得起照抄。
     */
    static final String USAGE = ListingMessages.MERCHANT_REVIEW_USAGE;

    private final MerchantService service;
    private final FederationAdminGuard guard;

    public MerchantReviewCommandHandler(MerchantService service, FederationAdminGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        if (!guard.isAdmin(ctx.userId())) {
            return null;
        }

        Parsed parsed = Parsed.of(ctx.commandArgs().orElse(null));
        if (parsed == null) {
            return reply(ctx, USAGE);
        }

        Optional<Merchant> found = service.find(parsed.merchantId());
        if (found.isEmpty()) {
            return reply(ctx, ListingMessages.merchantNotFound(parsed.merchantId()));
        }

        Merchant.Status current = Merchant.parse(found.get().getStatus());
        if (current != Merchant.Status.SUBMITTED && current != Merchant.Status.UNDER_REVIEW
                && current != Merchant.Status.NEED_MORE) {
            return reply(ctx, ListingMessages.merchantNotReviewable(current));
        }
        try {
            if (current != Merchant.Status.UNDER_REVIEW) {
                service.beginReview(parsed.merchantId(), ctx.userId());
            }
            service.decide(parsed.merchantId(), parsed.decision(), ctx.userId());
        } catch (TggException ex) {
            // 业务拒绝（如「不能复核自己的商家申请」）：如实回显原因。
            // 不让它穿透到分发层——那会变成静默失败，而「结论没写进去却不告诉复核人」更危险。
            return reply(ctx, ex.getMessage());
        }
        return reply(ctx, ListingMessages.merchantReviewed(parsed.merchantId(), parsed.decision()));
    }

    /** 命令操作数解析结果：目标商家编号 + 复核结论。 */
    private record Parsed(long merchantId, Merchant.Status decision) {

        /** 解析失败（缺参数 / 编号非数字 / 结论词不认识）返回 {@code null}。 */
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
            Merchant.Status decision = decisionOf(parts[1].trim().toLowerCase(Locale.ROOT));
            return decision == null ? null : new Parsed(merchantId, decision);
        }

        private static Merchant.Status decisionOf(String word) {
            return switch (word) {
                case "approve", "approved" -> Merchant.Status.APPROVED;
                case "reject", "rejected" -> Merchant.Status.REJECTED;
                case "need-more", "need_more", "needmore" -> Merchant.Status.NEED_MORE;
                default -> null;
            };
        }
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

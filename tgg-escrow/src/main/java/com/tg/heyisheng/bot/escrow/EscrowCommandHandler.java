package com.tg.heyisheng.bot.escrow;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.Confirm;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@code /escrow <动作> [参数]} —— 担保交易的统一入口（模块十二 · Wave 1 的 Bot 内 4 项之一）。
 *
 * <p><b>为什么是「单命令 + 子命令」而不是九条独立命令</b>：本项目已有先例
 * （{@code /data_breach report N}）。资金流程是一串<b>有序动作</b>，凑成一条命令用户只需记一个词。各动作：
 * <ul>
 *   <li>{@code open 卖家userId 金额} —— 买方发起（发起者即买家）；</li>
 *   <li>{@code confirm|lock|deliver|release 订单号} —— 主流程四步（身份由服务层把关）；</li>
 *   <li>{@code dispute|cancel 订单号 理由} —— 争议与协商取消（理由必填）；</li>
 *   <li>{@code status 订单号} / {@code list} —— 查询。</li>
 * </ul>
 *
 * <p><b>确认卡</b>：整条命令标 {@code Confirm.ALWAYS}——它承载资金动作。查询也走确认（多点一下），
 * 这是刻意用可用性换安全：资金命令不该有"手滑即执行"的路径。
 *
 * <p><b>可见性</b>：{@code publicCommand = true}——担保交易是自助功能，任何人可发起；
 * 每一步能否执行由<b>服务层的身份闸门</b>决定，而不是靠可见性隐藏
 * （本项目教训：把校验寄托在"命令对谁可见"上，换个入口就绕过了）。
 *
 * <p><b>进度通知</b>：状态推进成功后按 {@link EscrowNotifier} 双通道通知交易对方
 * （群内 + 私聊，各自可开关）。通知失败不影响已完成的资金动作——通知器自行吞异常。
 */
@BotCommand(value = "escrow", description = "担保交易：发起 / 托管 / 交付 / 验收 / 争议 / 查询",
        confirm = Confirm.ALWAYS, category = MenuCategory.ESCROW, publicCommand = true)
@ConditionalOnProperty(prefix = "tgg.escrow", name = "enabled", havingValue = "true")
public class EscrowCommandHandler implements CommandHandler {

    private final EscrowService service;
    private final EscrowNotifier notifier;

    public EscrowCommandHandler(EscrowService service, EscrowNotifier notifier) {
        this.service = service;
        this.notifier = notifier;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        String args = ctx.commandArgs().orElse(null);
        if (args == null || args.isBlank()) {
            return reply(ctx, EscrowMessages.USAGE);
        }
        String[] parts = args.trim().split("\\s+", 3);
        String action = parts[0].toLowerCase(Locale.ROOT);
        try {
            return switch (action) {
                case "open" -> open(ctx, parts);
                case "confirm" -> step(ctx, parts, EscrowMessages.ACTION_CONFIRM,
                        () -> service.confirm(orderId(parts), ctx.userId()),
                        order -> notifyParty(order, ctx, order.getBuyerUserId(),
                                EscrowMessages.awaitingDeposit(order.getId())));
                case "lock" -> step(ctx, parts, EscrowMessages.ACTION_LOCK,
                        () -> service.lock(orderId(parts), ctx.userId()),
                        order -> notifyParty(order, ctx, order.getSellerUserId(),
                                EscrowMessages.lockedNotifySeller(order.getId())));
                case "deliver" -> step(ctx, parts, EscrowMessages.ACTION_DELIVER,
                        () -> service.deliver(orderId(parts), ctx.userId()),
                        order -> notifyParty(order, ctx, order.getBuyerUserId(),
                                EscrowMessages.deliveredNotifyBuyer(order.getId(),
                                        service.disputeWindowDays())));
                case "release" -> step(ctx, parts, EscrowMessages.ACTION_RELEASE,
                        () -> service.release(orderId(parts), ctx.userId()),
                        order -> notifyParty(order, ctx, order.getSellerUserId(),
                                EscrowMessages.released(order.getId())));
                case "dispute" -> withReason(ctx, parts, EscrowMessages.ACTION_DISPUTE,
                        (id, reason) -> service.dispute(id, ctx.userId(), reason));
                case "cancel" -> withReason(ctx, parts, EscrowMessages.ACTION_CANCEL,
                        (id, reason) -> service.cancel(id, ctx.userId(), reason));
                case "status" -> status(ctx, parts);
                case "list" -> list(ctx);
                default -> reply(ctx, EscrowMessages.USAGE);
            };
        } catch (TggException ex) {
            return reply(ctx, describeFailure(ex.getMessage()));
        }
    }

    // ─────────────── 动作 ───────────────

    private BotApiMethod<?> open(UpdateContext ctx, String[] parts) {
        if (parts.length < 3) {
            return reply(ctx, EscrowMessages.USAGE);
        }
        long sellerId = parseLong(parts[1]);
        BigDecimal amount = parseAmount(parts[2]);
        if (sellerId <= 0 || amount == null) {
            return reply(ctx, EscrowMessages.USAGE);
        }
        EscrowOrder order = service.open(ctx.userId(), sellerId, amount, null);
        return reply(ctx, EscrowMessages.created(order.getId(), amount.toPlainString(), order.getCurrency()));
    }

    /** 无理由的状态迁移（confirm / lock / deliver / release），成功后可带一条通知。 */
    private BotApiMethod<?> step(UpdateContext ctx, String[] parts, String label,
                                 Supplier<Optional<EscrowOrder>> action,
                                 Consumer<EscrowOrder> onSuccess) {
        if (parts.length < 2) {
            return reply(ctx, EscrowMessages.USAGE);
        }
        long id = parseLong(parts[1]);
        if (id <= 0) {
            return reply(ctx, EscrowMessages.USAGE);
        }
        Optional<EscrowOrder> after = action.get();
        return after
                .map(order -> {
                    onSuccess.accept(order);
                    return reply(ctx, EscrowMessages.advanced(label, order.getId(),
                            EscrowMessages.stateName(order.getState())));
                })
                .orElseGet(() -> reply(ctx, EscrowMessages.notFound(id)));
    }

    /** 带理由的动作（dispute / cancel）。 */
    private BotApiMethod<?> withReason(UpdateContext ctx, String[] parts, String label, ReasonAction action) {
        if (parts.length < 3) {
            return reply(ctx, EscrowMessages.USAGE);
        }
        long id = parseLong(parts[1]);
        if (id <= 0) {
            return reply(ctx, EscrowMessages.USAGE);
        }
        Optional<EscrowOrder> after = action.apply(id, parts[2].trim());
        return after
                .map(order -> reply(ctx, EscrowMessages.advanced(label, order.getId(),
                        EscrowMessages.stateName(order.getState()))))
                .orElseGet(() -> reply(ctx, EscrowMessages.notFound(id)));
    }

    private BotApiMethod<?> status(UpdateContext ctx, String[] parts) {
        if (parts.length < 2) {
            return reply(ctx, EscrowMessages.USAGE);
        }
        long id = parseLong(parts[1]);
        if (id <= 0) {
            return reply(ctx, EscrowMessages.USAGE);
        }
        return service.find(id)
                .map(order -> reply(ctx, EscrowMessages.statusLine(order.getId(),
                        EscrowMessages.stateName(order.getState()),
                        order.getAmount().toPlainString(), order.getCurrency(),
                        String.valueOf(order.getBuyerUserId()), String.valueOf(order.getSellerUserId()))))
                .orElseGet(() -> reply(ctx, EscrowMessages.notFound(id)));
    }

    private BotApiMethod<?> list(UpdateContext ctx) {
        List<EscrowOrder> mine = service.ordersOf(ctx.userId());
        if (mine.isEmpty()) {
            return reply(ctx, EscrowMessages.LIST_EMPTY);
        }
        List<EscrowOrder> sorted = mine.stream()
                .sorted(Comparator.comparing(EscrowOrder::getId))
                .toList();
        StringBuilder sb = new StringBuilder(EscrowMessages.listHeader(sorted.size()));
        for (EscrowOrder order : sorted) {
            sb.append('\n').append(EscrowMessages.listItem(order.getId(),
                    EscrowMessages.stateName(order.getState()),
                    order.getAmount().toPlainString(), order.getCurrency()));
        }
        return reply(ctx, sb.toString());
    }

    // ─────────────── 工具 ───────────────

    /** 把进度通知发给交易对方（双通道按开关走；通知器自行吞异常，不影响已完成的资金动作）。 */
    private void notifyParty(EscrowOrder order, UpdateContext ctx, long recipientUserId, String text) {
        if (recipientUserId == ctx.userId()) {
            return; // 操作者即对方（如单人测试场景）：不给自己发通知
        }
        notifier.notifyParty(recipientUserId, ctx.chatId(), text);
    }

    private static String describeFailure(String message) {
        return EscrowMessages.FAILURE_PREFIX + message;
    }

    private static long orderId(String[] parts) {
        return parseLong(parts[1]);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private static BigDecimal parseAmount(String value) {
        try {
            BigDecimal amount = new BigDecimal(value.trim());
            return amount.signum() > 0 ? amount : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }

    /** 带理由的动作签名。 */
    @FunctionalInterface
    private interface ReasonAction {
        Optional<EscrowOrder> apply(long orderId, String reason);
    }
}

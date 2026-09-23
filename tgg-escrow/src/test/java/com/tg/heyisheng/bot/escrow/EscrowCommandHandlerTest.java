package com.tg.heyisheng.bot.escrow;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 担保交易命令入口的分发与参数解析守卫。
 *
 * <p>守三件事：
 * <ol>
 *   <li><b>参数非法即回用法</b>，绝不把脏参数递进服务层（否则用户拿到的是"服务层异常"而不是指引）；</li>
 *   <li><b>服务层异常翻译成可读回执</b>——身份越权/状态非法必须让用户看见原因，不得静默；</li>
 *   <li><b>订单不存在要给出去路</b>（VOICE：拒绝必给去处）。</li>
 * </ol>
 */
class EscrowCommandHandlerTest {

    private static final long USER = 11L;
    private static final long CHAT = -100L;

    private final EscrowService service = mock(EscrowService.class);
    private final EscrowNotifier notifier = mock(EscrowNotifier.class);
    private final EscrowCommandHandler handler = new EscrowCommandHandler(service, notifier);

    private static UpdateContext ctx(String args) {
        UpdateContext context = mock(UpdateContext.class);
        when(context.userId()).thenReturn(USER);
        when(context.chatId()).thenReturn(CHAT);
        when(context.commandArgs()).thenReturn(Optional.ofNullable(args));
        return context;
    }

    private static String textOf(BotApiMethod<?> reply) {
        return ((SendMessage) reply).getText();
    }

    @Test
    void missingArgsReturnsUsage() {
        assertThat(textOf(handler.handle(ctx(null)))).isEqualTo(EscrowMessages.USAGE);
        assertThat(textOf(handler.handle(ctx("   ")))).isEqualTo(EscrowMessages.USAGE);
    }

    @Test
    void unknownActionReturnsUsage() {
        assertThat(textOf(handler.handle(ctx("frobnicate")))).isEqualTo(EscrowMessages.USAGE);
        verify(service, never()).open(anyLong(), anyLong(), any(), any());
    }

    @Test
    void openWithBadParamsReturnsUsageInsteadOfCallingService() {
        // 缺参数
        assertThat(textOf(handler.handle(ctx("open")))).isEqualTo(EscrowMessages.USAGE);
        // 卖家 id 非数字
        assertThat(textOf(handler.handle(ctx("open abc 100")))).isEqualTo(EscrowMessages.USAGE);
        // 金额非正
        assertThat(textOf(handler.handle(ctx("open 22 0")))).isEqualTo(EscrowMessages.USAGE);
        // 金额非数字
        assertThat(textOf(handler.handle(ctx("open 22 xyz")))).isEqualTo(EscrowMessages.USAGE);

        verify(service, never()).open(anyLong(), anyLong(), any(), any());
    }

    @Test
    void openWithGoodParamsCallsServiceAndRepliesWithOrderId() {
        // 用 mock 而非 new：生产中 service.open() 返回的是已 save 的订单（id 必非 null）。
        // 直接 new 出的实体 id 为 null——那模拟的是不可能的状态，且会让断言失焦。
        EscrowOrder order = mock(EscrowOrder.class);
        when(order.getId()).thenReturn(42L);
        when(order.getCurrency()).thenReturn("USDT");
        when(service.open(eq(USER), eq(22L), any(), any())).thenReturn(order);

        String text = textOf(handler.handle(ctx("open 22 100")));

        assertThat(text).contains("#42").contains("USDT");
        verify(service).open(eq(USER), eq(22L), any(), any());
    }

    @Test
    void statusOnMissingOrderGivesAWayOut() {
        when(service.find(7L)).thenReturn(Optional.empty());

        String text = textOf(handler.handle(ctx("status 7")));

        assertThat(text)
                .as("订单不存在时必须给出去路（VOICE：拒绝必给去处）")
                .contains("/escrow list");
    }

    @Test
    void listWithoutOrdersGivesAWayOut() {
        when(service.ordersOf(USER)).thenReturn(List.of());

        assertThat(textOf(handler.handle(ctx("list")))).contains("/escrow open");
    }

    @Test
    void serviceRejectionIsSurfacedNotSilenced() {
        when(service.release(eq(1L), anyLong()))
                .thenThrow(new TggException("该操作仅限买方（订单 #1，操作者不是买方）"));

        String text = textOf(handler.handle(ctx("release 1")));

        assertThat(text)
                .as("身份越权必须让用户看见原因，不得吞成静默")
                .contains(EscrowMessages.FAILURE_PREFIX);
    }

    @Test
    void disputeRequiresReasonArgument() {
        // 只有动作与订单号、缺理由 → 回用法，不调用服务层
        assertThat(textOf(handler.handle(ctx("dispute 1")))).isEqualTo(EscrowMessages.USAGE);
        verify(service, never()).dispute(anyLong(), anyLong(), anyString());
    }

    @Test
    void lockNotifiesTheSeller() {
        EscrowOrder order = mock(EscrowOrder.class);
        when(order.getId()).thenReturn(1L);
        when(order.getSellerUserId()).thenReturn(22L);
        when(order.getState()).thenReturn("LOCKED");
        when(service.lock(eq(1L), eq(USER))).thenReturn(Optional.of(order));

        handler.handle(ctx("lock 1"));

        // 托管成功后必须通知卖方交付——双通道通知的关键触发点
        verify(notifier).notifyParty(eq(22L), eq(CHAT), anyString());
    }

    @Test
    void failedTransitionDoesNotNotify() {
        when(service.release(eq(1L), anyLong())).thenReturn(Optional.empty());

        handler.handle(ctx("release 1"));

        verify(notifier, never()).notifyParty(anyLong(), any(), anyString());
    }
}

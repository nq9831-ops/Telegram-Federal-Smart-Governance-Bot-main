package com.tg.heyisheng.bot.escrow;

/**
 * 模块十二 · 担保交易的<b>用户可见文案</b>（唯一来源）。
 *
 * <p><b>纪律</b>（对齐 {@code VOICE.md}）：
 * <ul>
 *   <li>结论先行（先给发生了什么，再给出路）；</li>
 *   <li>拒绝必给去处——凡"不可/不能"后必须跟"你可以…"或"找谁"；</li>
 *   <li>命令示例<b>可照抄</b>（给完整参数，不给 {@code <参数>} 这类模板）；</li>
 *   <li>字面量不得含模板符号（{@code <} {@code >} {@code |}）或未填占位符。</li>
 * </ul>
 *
 * <p><b>为什么要集中成类</b>：本项目的既有教训是文案散落在调用点后，
 * "改一处漏一处"必然发生；且状态名若各处各写一份，同一条订单在不同界面上会显示成不同的中文。
 */
public final class EscrowMessages {

    private EscrowMessages() {
    }

    /**
     * 订单状态 → 用户可见中文名。
     *
     * <p>未知状态<b>不猜</b>：原样带出并加前缀，便于排查（静默映射到某个默认名会让
     * 状态机漂移在界面上完全不可见）。
     */
    public static String stateName(String state) {
        if (state == null) {
            return "状态未知";
        }
        return switch (state) {
            case "OPEN" -> "待卖方确认";
            case "CONFIRMED" -> "待托管资金";
            case "LOCKED" -> "资金托管中，待交付";
            case "DELIVERED" -> "已交付，待验收";
            case "DISPUTED" -> "争议中，等待裁决";
            case "RELEASED" -> "已完成（资金已放款）";
            case "REFUNDED" -> "已退款";
            case "CANCELLED" -> "已取消";
            default -> "未知状态：" + state;
        };
    }

    /** {@code /escrow_open} 用法——可照抄的完整示例。 */
    public static final String OPEN_USAGE =
            "用法：\n/escrow_open 100 USDT —— 发起一笔担保交易，钱先托管，验收后才放款给卖家";

    /** 订单创建成功（含编号与金额，便于双方对账）。 */
    public static String created(long orderId, String amount, String currency) {
        return "担保交易已创建（编号 #" + orderId + "，金额 " + amount + " " + currency
                + "）。已通知卖方确认；卖方确认后你再托管资金。";
    }

    /** 卖方确认后，提示买方托管（给出路）。 */
    public static String awaitingDeposit(long orderId) {
        return "卖方已确认（编号 #" + orderId + "）。现在可以托管资金了——"
                + "托管后资金由合约保管，验收前谁也拿不走。";
    }

    /** 资金已托管：通知卖方可以交付。 */
    public static String lockedNotifySeller(long orderId) {
        return "买家已托管资金（编号 #" + orderId + "）。请按约定交付，交付后提醒买家验收。";
    }

    /** 卖方已交付：提示买方验收，并说明超时后果。 */
    public static String deliveredNotifyBuyer(long orderId, int disputeWindowDays) {
        return "卖家已标记交付（编号 #" + orderId + "）。确认无误就验收放款；"
                + "有问题请在 " + disputeWindowDays + " 天内发起争议，超时将按约定自动放款给卖家。";
    }

    /** 资金已放款（终态回执）。 */
    public static String released(long orderId) {
        return "交易完成（编号 #" + orderId + "），资金已放款给卖家。感谢守约。";
    }

    /** 资金已退款（终态回执）。 */
    public static String refunded(long orderId) {
        return "交易已结束（编号 #" + orderId + "），资金已退回买家。";
    }

    /** 订单已取消（未托管时的退出路径）。 */
    public static String cancelled(long orderId) {
        return "交易已取消（编号 #" + orderId + "）。资金未曾托管，无需退款。";
    }

    /** 越权/不适用：拒绝必给出路。 */
    public static String notPartyToOrder(long orderId) {
        return "这笔交易（编号 #" + orderId + "）不是你的订单。"
                + "你可以用 /escrow_list 查看自己参与的订单。";
    }

    /** 已托管后不允许取消——给出正确路径。 */
    public static String cannotCancelAfterFundsHeld(long orderId) {
        return "这笔交易（编号 #" + orderId + "）的资金已经托管，不能直接取消。"
                + "你可以和对方协商由一方发起退款，或发起争议让联邦仲裁裁决。";
    }
}

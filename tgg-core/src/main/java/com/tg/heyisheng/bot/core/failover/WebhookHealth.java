package com.tg.heyisheng.bot.core.failover;

/**
 * webhook 健康探测的<b>三态</b>结果。
 *
 * <p><b>为什么是三态而非布尔</b>：二态把「探测通道故障」与「webhook 真的不健康」混为一谈——
 * 网络抖动 / DNS 失败 / 超时 / 鉴权失败会让探测拿不到<b>可信结论</b>，据此降级到长轮询毫无意义
 * （长轮询要走同一条网络、同一个 token），却因降级单向不可逆（见 KNOWN-ISSUES P2-7）
 * 把一次通信故障变成永久切换。同项目的 {@code TelegramGroupLinkVerifier} 早已用
 * OK/FAIL/ERROR 三态分离同一类问题（「探测失败 ≠ 群失效」），本枚举让两个探测器语义对齐。
 *
 * <p><b>只有 {@link #UNHEALTHY} 计入降级判定</b>；{@link #UNKNOWN} 不改变连续失败计数
 * （既不清零、也不累加——它是「没有信息」）。
 */
public enum WebhookHealth {

    /** Telegram 明确答复：webhook 正常。（连续失败计数归零） */
    HEALTHY,

    /**
     * Telegram 明确答复：webhook 不健康（未配置 / 最近有投递错误）。
     * <b>这是唯一计入降级计数的状态</b>——只有它代表「Telegram 侧确认收不到推送」。
     */
    UNHEALTHY,

    /**
     * 未取得可信结论：网络不可达、非 2xx、响应体非法、或 Telegram 拒绝应答（{@code ok:false}）。
     * <b>不计入降级计数</b>——探测通道的问题不该被当作被探测对象的问题。
     */
    UNKNOWN
}

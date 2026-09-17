package com.tg.heyisheng.bot.federation;

/**
 * 入站处罚令的处理结果。
 *
 * <p>调用方（HTTP 端点）据它决定响应码：{@link #ACCEPTED}/{@link #DUPLICATE} → 200；
 * 其余 → 403（拒绝且**不落库**）。
 */
public enum FederationIngestResult {

    /** 首次接收，已落库。 */
    ACCEPTED,

    /** 该 {@code order_id} 已处理过（重复投递），未重复落库。 */
    DUPLICATE,

    /** 来源节点不在白名单内。 */
    REJECTED_UNKNOWN_NODE,

    /** 验签失败（签名缺失、被篡改、或非该节点私钥所签）。 */
    REJECTED_BAD_SIGNATURE
}

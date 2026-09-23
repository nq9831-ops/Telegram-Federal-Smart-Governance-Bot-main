package com.tg.heyisheng.bot.core.credit;

/**
 * 信用事件类型（模块七）。
 *
 * <p>各生产模块在命中时产生对应事件；{@code CreditRuleEngine} 据此决定分值增量。
 * 本枚举是契约的一部分——<b>新增「需要固定增量」的事件类型</b>（如交易完成加分）时，须同时登记进
 * {@code BuiltInCreditRules} 的 {@code TYPE_DELTAS} 类型表；未登记的类型按 {@code severity} 推增量，
 * 而该路径恒为 {@code ≤ 0}——<b>正增量类型漏登记 = 事件发了、分数没动</b>（静默失效）。
 */
public enum CreditEventType {

    /** 模块九：AI 审核命中（含硬红线，硬红线由事件的 {@code hardLine} 单独标记）。 */
    MODERATION_HIT,

    /**
     * 模块十一：<b>推翻案件后的反向补偿</b>——把某次审核命中<b>实际</b>扣掉的分退回。
     *
     * <p>⚠️ <b>不得经 {@code CreditService.apply} 处理</b>：apply 会用规则引擎按 severity <b>重算</b>增量，
     * 而补偿事件的增量是负的（−30）——那会把它当成<b>又一次扣分</b>；即便方向修对，
     * 重算量在「触底夹取」场景下也与实际扣减量不同（10 分时硬红线实际只扣 10，重算却是 100）。
     * 它只能由 {@code CreditService.reverseOf} 处理：退分量取自原流水的
     * {@code score_before / score_after}。
     */
    MODERATION_REVERSAL,

    /** 模块三：违禁词命中。 */
    BANNED_WORD_HIT,

    /** 模块三：反刷屏命中（重复内容）。 */
    SPAM_REPEAT,

    /** 模块四：入群验证超时未通过。 */
    JOIN_TIMEOUT,

    /** 模块四：验证失败（预留——当前准入流程为"超时即移出"，未单独产生失败事件）。 */
    JOIN_VERIFICATION_FAIL,

    /**
     * 模块十二：担保交易<b>正常完成</b>（买方确认收货或超时自动放款）→ <b>加分</b>。
     *
     * <p>⚠️ <b>唯一产出正增量的类型</b>：既有规则引擎只按 severity 推增量（恒 {@code ≤ 0}），
     * 故本类型必须登记进 {@code BuiltInCreditRules.TYPE_DELTAS}，否则会被按 severity=NONE 记 0 分
     * ——"事件发了、分数没动"的静默失效。
     */
    ESCROW_COMPLETED,

    /**
     * 模块十二：担保交易<b>欺诈</b>（仲裁裁定欺诈成立）→ 一扣到底。
     *
     * <p>与硬红线同款纪律：<b>不看</b>上报的 severity，直接触底——低等级不得掩盖资金欺诈。
     */
    ESCROW_FRAUD
}

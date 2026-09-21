package com.tg.heyisheng.bot.core.credit;

/**
 * 信用事件类型（模块七）。
 *
 * <p>各生产模块在命中时产生对应事件；{@code CreditRuleEngine} 据此决定分值增量。
 * 本枚举是契约的一部分——新增事件类型须同时更新规则引擎的内置规则表，
 * 否则该类型事件会被规则引擎按"未知类型"处理（记日志、不扣分）。
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
    JOIN_VERIFICATION_FAIL
}

package com.tg.heyisheng.bot.core.credit;

/**
 * 信用主体类型（模块七 · 三套信用分）。
 *
 * <p>与用户 2026-09-17 确认的三套划分一一对应：<b>个人 / 群组 / 商家</b>。
 * 三套分共用一张账本表（{@code credit_scores}），按本枚举区分，避免三张结构相同的表。
 */
public enum CreditSubjectType {

    /** 个人（成员）信用分：{@code subjectId} = Telegram userId。 */
    INDIVIDUAL,

    /** 群组信用分：{@code subjectId} = Telegram chatId（负数）。 */
    GROUP,

    /** 商家（商业主体）信用分：{@code subjectId} = 商户 id。 */
    MERCHANT
}

package com.tg.heyisheng.bot.core.notify;

/**
 * 通知类别（模块十二引入）——与 {@link NotificationLevel} <b>正交</b>的「额度分池」维度。
 *
 * <p><b>为什么需要它</b>：配额原本只按「收件人 × 级别」计（{@code level:recipientId}），
 * 于是担保交易的流程通知（托管/交付/超时/争议）会与治理通知（封禁告知、信用分变动、
 * 申诉进度）<b>共用同一个额度池</b>——交易量一大，治理侧额度就被耗尽，
 * 用户会在无人知晓的情况下错过权益通知。
 *
 * <p><b>语义边界</b>：类别只决定<b>配额分池</b>，不改变级别自身的规则——
 * {@code URGENT} 依旧不限、{@code IMPORTANT} 依旧 3 条/小时、{@code NORMAL} 依旧 1 条/小时 + 5 条/天。
 */
public enum NotificationCategory {

    /** 治理类（默认）：封禁、信用分、申诉、群组状态等既有通知。 */
    GOVERNANCE,

    /** 担保交易类（模块十二）：托管、交付、超时、争议、裁决等资金流程通知。 */
    ESCROW
}

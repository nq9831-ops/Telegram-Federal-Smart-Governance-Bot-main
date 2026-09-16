package com.tg.heyisheng.bot.core.moderation;

/**
 * 人工复核状态。
 *
 * <p>新建的队列项一律为 {@link #PENDING}；裁决（{@code APPROVED} / {@code REJECTED}）
 * 属后台（模块十一）职责，本阶段只落库、不流转。
 */
public enum ReviewStatus {

    /** 待人工复核。 */
    PENDING,

    /** 复核确认违规。 */
    APPROVED,

    /** 复核判定为误报。 */
    REJECTED
}

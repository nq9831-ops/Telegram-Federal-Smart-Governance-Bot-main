package com.tg.heyisheng.bot.core.platform;

/**
 * 平台层能力点（模块十一 · 权限模型）。
 *
 * <p><b>为什么新开一个枚举，而不扩展 {@code Permission} / {@code Role}</b>：
 * 后者是<b>群内</b>语义（{@code <chatId>:<userId>[:role]}），被 {@code @BotCommand(requiredPermission=…)}
 * 消费。若把平台语义混进去，群内命令就能声明平台权——那是最不该出现的越权。
 * 故平台能力单列，与本项目「四套平台白名单不复用群内 RBAC」的既有取舍一致。
 *
 * <p><b>超管天然全有</b>：{@code SUPER_ADMIN} 不受本枚举限制；本枚举用于给<b>操作员</b>或
 * TG 用户授予具体能力（见 {@code platform_grants}）。
 *
 * <p><b>与四套既有白名单的关系</b>：能力点是这些白名单的<b>统一来源</b>。账本为空时各 guard
 * 回落到原有配置键（升级不破坏线上授权）。
 */
public enum PlatformPermission {

    /** 裁决复核案件（对应原 {@code tgg.moderation.reviewers}）。 */
    REVIEW_DECIDE,

    /** 写平台配置 / 触发重启（对应原 {@code tgg.admin.config-admins}）。 */
    CONFIG_WRITE,

    /** 联邦治理管理（对应原 {@code tgg.federation.admins}）。 */
    FEDERATION_ADMIN,

    /**
     * 商家资质复核（原 {@code tgg.merchant.reviewers}）。<b>已不再授予任何命令</b>
     * （2026-09-22 二次拍板：「商家只有联邦管理员才可以审核处理」——商家管理三件归
     * {@link #FEDERATION_ADMIN}）。保留本枚举值仅为让平台账本存量行仍可解析；新授权勿再使用。
     */
    MERCHANT_REVIEW,

    /** 分配权限（<b>仅超管</b>——授予它等于给出一条提权路，故任何 API 都不得把它授予非超管主体）。 */
    GRANT_MANAGE,

    /** 触发系统重启（与 CONFIG_WRITE 分开：重启是不可逆的对外动作）。 */
    SYSTEM_RESTART,

    /** 查看审计（只读）。 */
    AUDIT_READ,

    /**
     * 查看信用账本 / 流水（只读）。
     *
     * <p>与 {@link #AUDIT_READ} 单列：审计（谁做了什么动作）与信用（谁被扣了什么分）是两条
     * 不同的可见面，读审计不等于读账本——沿用本项目「一个面一个能力点」的既有取舍。
     */
    CREDIT_READ
}

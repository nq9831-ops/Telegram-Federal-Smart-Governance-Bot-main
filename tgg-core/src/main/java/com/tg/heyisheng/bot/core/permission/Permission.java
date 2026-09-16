package com.tg.heyisheng.bot.core.permission;

/**
 * 权限点。
 *
 * <p>{@link #NONE} 表示「不需要任何权限」——未显式声明权限的命令默认落到这里，
 * 即全员可用（保持切片 1/2 里 {@code /echo} 这类命令的行为不变）。
 *
 * <p>权限的<b>持久化来源</b>不在此包内：本切片只定义模型与判定，
 * 真正的群组管理员配置归属模块三（群组管理），后台管理员归属模块十一（Web 后台）。
 */
public enum Permission {

    /** 无需权限：任何人可用。 */
    NONE,

    /** 封禁/禁言等处置类操作。 */
    BAN_USER,

    /** 修改群组配置（功能开关、词库等）。 */
    MANAGE_CONFIG,

    /** 提交新骗局规则（V5.0 的 /teach）。 */
    TEACH_RULE
}

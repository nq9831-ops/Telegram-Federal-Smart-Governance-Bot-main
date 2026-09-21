package com.tg.heyisheng.bot.admin.identity;

/**
 * 后台账号角色（{@code admin_accounts.role}）。
 *
 * <p><b>超管唯一且不可被创建</b>：{@link #SUPER_ADMIN} 只能由环境变量引导创建
 * （见 {@code AdminBootstrap}），<b>任何 API 都不得创建第二个超管</b>——
 * 否则「谁能建超管」本身就是一条提权口。
 */
public enum AdminRole {

    /** 超级管理员：纯 Web 身份（非 TG 用户），对平台有全部掌控。 */
    SUPER_ADMIN,

    /** 操作员：由超管创建并分配权限。 */
    OPERATOR
}

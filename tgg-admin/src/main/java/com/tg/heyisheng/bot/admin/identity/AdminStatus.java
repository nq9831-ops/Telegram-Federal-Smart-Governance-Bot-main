package com.tg.heyisheng.bot.admin.identity;

/**
 * 后台账号状态（{@code admin_accounts.status}）。
 *
 * <p>停用即不可登录；已在线的会话经「强制下线」即时失效（服务端会话可即时吊销）。
 */
public enum AdminStatus {

    /** 正常可用。 */
    ACTIVE,

    /** 已停用：不可登录；调用方须同时吊销其全部会话。 */
    DISABLED
}

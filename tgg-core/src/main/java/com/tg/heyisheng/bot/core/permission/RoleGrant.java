package com.tg.heyisheng.bot.core.permission;

/**
 * 一条角色授权：某用户在某群拥有某角色。
 *
 * <p>与 {@link RoleSource#roleOf} 的**单点查询**互补：本记录用于需要**枚举**已授权项的场景
 * （典型是「按授权分层注册客户端命令菜单」——要知道该给哪些 `(chatId, userId)` 注册管理命令）。
 *
 * <p>{@code chatId} / {@code userId} 用原始 {@code long}（而非包装类型）：授权项一旦存在于
 * {@link RoleSource} 中，两者必非空；可空性在写入侧已被 {@link InMemoryRoleSource#assign} 挡住。
 */
public record RoleGrant(long chatId, long userId, Role role) {
}

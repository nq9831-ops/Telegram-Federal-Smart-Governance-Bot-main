package com.tg.heyisheng.bot.core.middleware;

import com.tg.heyisheng.bot.common.model.UpdateContext;

import java.util.Set;

/**
 * 权限校验中间件（切片 1 为静态最简实现）。
 *
 * <p>当前只提供基于静态管理员名单的 {@link #isAdmin(Long)} 判定，供管理类命令使用；
 * <b>不阻断</b>任何更新——因为切片 1 尚无「哪些命令需要何种权限」的元数据。
 * 完整 RBAC（Role + PermissionChecker + 持久化）在切片 3 实现。
 */
public class PermissionMiddleware implements Middleware {

    private final Set<Long> adminUserIds;

    public PermissionMiddleware(Set<Long> adminUserIds) {
        this.adminUserIds = Set.copyOf(adminUserIds);
    }

    @Override
    public boolean handle(UpdateContext ctx, MiddlewareChain chain) {
        return true;
    }

    /** 是否为静态配置的管理员。后续命令处理器可据此放行管理类命令。 */
    public boolean isAdmin(Long userId) {
        return userId != null && adminUserIds.contains(userId);
    }
}

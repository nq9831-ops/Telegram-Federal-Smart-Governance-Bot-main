package com.tg.heyisheng.bot.core.middleware;

import com.tg.heyisheng.bot.common.model.UpdateContext;

import java.util.Set;

/**
 * 【已废弃】权限校验中间件。
 *
 * <p><b>废弃原因</b>：中间件看不到「即将执行哪条命令」，因此无法判断该命令需要什么权限——
 * 它只能做到「链上不停」，实际上是个空转节点；而 {@code isAdmin} 从未被任何生产代码调用。
 * 权限已改由 {@link com.tg.heyisheng.bot.core.dispatch.CommandDispatcher} 按
 * {@code @BotCommand(requiredPermission = ...)} 的声明判定。
 *
 * <p>本类已从 {@link com.tg.heyisheng.bot.core.config.TggCoreConfiguration} 的中间件链中移除，
 * 保留文件仅为避免误伤可能的外部引用；确认无引用后可安全删除。
 */
@Deprecated(since = "切片 3 收尾", forRemoval = true)
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

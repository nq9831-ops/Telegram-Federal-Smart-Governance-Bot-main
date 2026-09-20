package com.tg.heyisheng.bot.core.middleware;

import com.tg.heyisheng.bot.common.model.UpdateContext;

/**
 * 中间件：在命令分发之前对更新做串行处理。
 *
 * <p>链式处理顺序由 {@link MiddlewareChain} 决定（注册顺序即执行顺序，
 * 具体顺序在装配处定义：认证 → 群组配置加载 → 限流）。
 * <b>权限校验不在链里</b>——中间件看不到「即将执行哪条命令」，无法得知该命令需要什么权限，
 * 故判定归 {@code CommandDispatcher}（{@code PermissionMiddleware} 已不参与装配）。
 */
@FunctionalInterface
public interface Middleware {

    /**
     * @param ctx   更新上下文（不含消息正文）
     * @param chain 当前链，供需要"显式放行到下一环"的实现使用
     * @return {@code true} 继续执行后续中间件；{@code false} 中断整条链
     */
    boolean handle(UpdateContext ctx, MiddlewareChain chain);
}

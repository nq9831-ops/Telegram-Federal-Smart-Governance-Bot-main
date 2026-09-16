package com.tg.heyisheng.bot.core.middleware;

import com.tg.heyisheng.bot.common.model.UpdateContext;

/**
 * 群组配置加载中间件。
 *
 * <p>切片 1 为占位实现：配置源尚未接入（后续切片改为从 MySQL / Redis 读取群组级配置，
 * 如功能开关、词库、管理员名单）。此处只保证链上存在该环且不阻断流转。
 */
public class GroupConfigMiddleware implements Middleware {

    @Override
    public boolean handle(UpdateContext ctx, MiddlewareChain chain) {
        // 切片 1：无持久化配置源，直接放行。
        return true;
    }
}

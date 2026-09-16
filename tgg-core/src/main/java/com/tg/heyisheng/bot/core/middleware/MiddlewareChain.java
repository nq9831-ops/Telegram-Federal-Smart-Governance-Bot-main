package com.tg.heyisheng.bot.core.middleware;

import com.tg.heyisheng.bot.common.model.UpdateContext;

import java.util.List;

/**
 * 中间件链：按注册顺序串行执行，任一中间件返回 {@code false} 即中断。
 *
 * <p>链是<b>不可变</b>的——构造后中间件列表固定，可安全复用于并发请求。
 */
public class MiddlewareChain {

    private final List<Middleware> middlewares;

    public MiddlewareChain(List<Middleware> middlewares) {
        this.middlewares = List.copyOf(middlewares);
    }

    /**
     * 顺序执行全部中间件。
     *
     * @return {@code true} 全部通过（可进入命令分发）；{@code false} 被中断
     */
    public boolean proceed(UpdateContext ctx) {
        for (Middleware middleware : middlewares) {
            if (!middleware.handle(ctx, this)) {
                return false;
            }
        }
        return true;
    }

    public int size() {
        return middlewares.size();
    }
}

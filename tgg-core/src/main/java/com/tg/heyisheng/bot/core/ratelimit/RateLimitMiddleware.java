package com.tg.heyisheng.bot.core.ratelimit;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.middleware.Middleware;
import com.tg.heyisheng.bot.core.middleware.MiddlewareChain;

/**
 * 三级限流中间件（链尾）：用户 → 群组 → 全局，任一层超限即中断。
 *
 * <p>三级独立计数：单个用户刷屏不影响他人，单群爆发不影响全局，
 * 全局配额则用于保护 Bot 自身不被 Telegram API 限流。
 */
public class RateLimitMiddleware implements Middleware {

    static final String GLOBAL_KEY = "global";

    private final RateLimiter userLimiter;
    private final RateLimiter groupLimiter;
    private final RateLimiter globalLimiter;

    public RateLimitMiddleware(RateLimiter userLimiter,
                               RateLimiter groupLimiter,
                               RateLimiter globalLimiter) {
        this.userLimiter = userLimiter;
        this.groupLimiter = groupLimiter;
        this.globalLimiter = globalLimiter;
    }

    @Override
    public boolean handle(UpdateContext ctx, MiddlewareChain chain) {
        if (ctx.userId() != null && !userLimiter.tryAcquire("u:" + ctx.userId())) {
            return false;
        }
        if (ctx.chatId() != null && !groupLimiter.tryAcquire("g:" + ctx.chatId())) {
            return false;
        }
        return globalLimiter.tryAcquire(GLOBAL_KEY);
    }
}

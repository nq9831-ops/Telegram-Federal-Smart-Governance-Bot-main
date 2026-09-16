package com.tg.heyisheng.bot.core.middleware;

import com.tg.heyisheng.bot.common.model.UpdateContext;

/**
 * 认证中间件（链首）。
 *
 * <p>切片 1 为最简实现：只要求更新携带可识别的发送者。
 * 完整身份校验（内部 API 签名、来源验证）留待后续切片。
 */
public class AuthenticationMiddleware implements Middleware {

    @Override
    public boolean handle(UpdateContext ctx, MiddlewareChain chain) {
        return ctx.userId() != null;
    }
}

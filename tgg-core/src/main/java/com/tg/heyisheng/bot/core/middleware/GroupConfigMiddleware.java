package com.tg.heyisheng.bot.core.middleware;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 群组配置加载中间件。
 *
 * <p>职责：按 chatId 取出该群配置，<b>挂到 {@link UpdateContext}</b> 供后续
 * handler 读取（上下文单实例贯通，所以挂上去的 handler 真能读到）；
 * 若该群的功能开关为关闭，则中断链路、不做任何处理。
 *
 * <p><b>未登记群组</b>走默认配置（启用）——避免"机器人进群后什么都不做"。
 *
 * <p><b>数据库故障</b>时 {@link GroupConfigService#findOrDefault} 会 fail-open
 * 并记 ERROR 日志，因此此处不需要重复处理异常。
 */
public class GroupConfigMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(GroupConfigMiddleware.class);

    private final GroupConfigService groupConfigService;

    public GroupConfigMiddleware(GroupConfigService groupConfigService) {
        this.groupConfigService = groupConfigService;
    }

    @Override
    public boolean handle(UpdateContext ctx, MiddlewareChain chain) {
        GroupConfigView config = groupConfigService.findOrDefault(ctx.chatId());
        ctx.attach(config);

        if (!config.enabled()) {
            // 记日志而非静默跳过：否则运维者会看到"机器人在群里完全不响应"却查不出原因
            log.info("群 {} 的功能开关为关闭，跳过本次处理", ctx.chatId());
            return false;
        }
        return true;
    }
}

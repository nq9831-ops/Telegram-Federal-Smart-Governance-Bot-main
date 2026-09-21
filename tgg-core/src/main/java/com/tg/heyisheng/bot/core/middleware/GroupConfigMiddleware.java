package com.tg.heyisheng.bot.core.middleware;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 群组配置加载中间件。
 *
 * <p>职责：按 chatId 取出该群配置，<b>挂到 {@link UpdateContext}</b> 供后续处理读取
 * （上下文单实例贯通，所以挂上去的 handler 真能读到）。
 *
 * <p><b>本中间件永不中断链路</b>（恒返回 {@code true}）。是否因「该群功能已关闭」而拒绝执行，
 * 由 {@link com.tg.heyisheng.bot.core.dispatch.CommandDispatcher} 按命令声明判断——
 * 因为只有那里才知道「即将执行哪条命令」。
 *
 * <p>⚠️ <b>不要把开关判断搬回这里</b>：本中间件曾在功能关闭时直接 {@code return false} 中断链，
 * 结果 {@code /disable} 之后连 {@code /enable} 都进不来，该群<b>永久锁死</b>、只能改数据库恢复。
 * 中断链 = 关闭态下所有命令（含恢复类命令）都不可达，这是已被实测证实的缺陷。
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
        // 只负责加载与挂载。是否因「功能已关闭」而拒绝执行，交由 CommandDispatcher 判断——
        // 中间件看不到「即将执行哪条命令」，无法知道该命令是否属于恢复类命令（如 /enable 必须放行）。
        // 早期实现曾在此处直接中断链，导致被停用的群永久锁死（/enable 自己都进不来），
        // 只能由运维改数据库恢复——那是实测暴露的真实缺陷。
        ctx.attach(groupConfigService.findOrDefault(ctx.chatId()));
        return true;
    }
}

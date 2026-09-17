package com.tg.heyisheng.bot.core.audit;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.stereotype.Component;

/**
 * 审计切面（模块十 §11.2）：对<b>所有命令处理器</b>记录一条审计。
 *
 * <p><b>为什么按类型切入而不是逐个标注</b>：原文要求「记录所有操作」。若靠给每个命令加
 * {@code @Auditable}，新增命令忘了标就静默漏审——那正是审计最不能出的错（漏审等于没审）。
 * 按类型切入后，新命令自动纳入。
 *
 * <p><b>异常必须继续抛</b>：切面记录 FAILURE 后原样抛出——审计不得吞掉业务异常，
 * 否则命令失败会被伪装成成功。
 *
 * <p><b>只记动作与结论，不记正文</b>：{@code detail} 只放异常类型名这类结构化信息。
 */
@Aspect
@Component
public class AuditAspect {

    private final AuditService auditService;

    public AuditAspect(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 覆盖全部命令处理器。
     *
     * <p><b>按接口匹配而不是按类名</b>：写成 {@code *CommandHandler.handle(..)} 只认类名后缀，
     * 一旦有人新增一个不叫 {@code XxxCommandHandler} 的处理器就会<b>静默漏审</b>
     * ——审计最危险的失效是漏记，不是记错。{@code this(CommandHandler)} 按接口判定，与命名无关。
     */
    @Around("execution(* com.tg.heyisheng.bot..*.handle(..)) "
            + "&& this(com.tg.heyisheng.bot.core.dispatch.CommandHandler)")
    public Object auditCommand(ProceedingJoinPoint joinPoint) throws Throwable {
        // 动作名取「真实目标类」而不是 joinPoint 的声明类型：JDK 动态代理下声明类型是接口
        // （会记成 CommandHandler#handle，全部命令一个样、毫无区分度）。
        // 这与 CommandRegistry 读 @BotCommand 遇的是同一类代理陷阱。
        String action = AopProxyUtils.ultimateTargetClass(joinPoint.getTarget()).getSimpleName()
                + "#handle";
        UpdateContext ctx = contextOf(joinPoint.getArgs());
        Long actorId = ctx == null ? null : ctx.userId();
        Long target = ctx == null ? null : ctx.chatId();

        try {
            Object result = joinPoint.proceed();
            auditService.record(actorId, action, target, AuditEntry.Outcome.SUCCESS, null);
            return result;
        } catch (Throwable ex) {
            // 只记异常类型名——异常消息可能包含用户数据（历史上就出过正文经异常泄露的坑）
            auditService.record(actorId, action, target, AuditEntry.Outcome.FAILURE,
                    ex.getClass().getSimpleName());
            throw ex;
        }
    }

    /** 从入参里找出 {@link UpdateContext}；命令处理器的签名就是 {@code handle(UpdateContext)}。 */
    private static UpdateContext contextOf(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof UpdateContext ctx) {
                return ctx;
            }
        }
        return null;
    }
}

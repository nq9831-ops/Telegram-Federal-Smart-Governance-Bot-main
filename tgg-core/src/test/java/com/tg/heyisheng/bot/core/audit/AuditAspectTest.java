package com.tg.heyisheng.bot.core.audit;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 审计切面测试（模块十 §11.2）——用 {@code AspectJProxyFactory} 手工织入，
 * 不启动 Spring 上下文即可验证「切点是否真的命中」与「成功/失败是否都留痕」。
 *
 * <p><b>为什么必须测「失败也留痕」</b>：审计最危险的失效不是记错，而是<b>漏记</b>——
 * 尤其命令抛异常那条路径，若只切正常返回，失败操作就会完全无迹可查。
 *
 * <p><b>为什么断言里带 {@link ActorType#TG_USER}</b>：命令路径的主体恒为 TG 用户；
 * 断言显式类型可防「有人把默认类型改成别的」时静默改变命令路径的审计归属。
 */
class AuditAspectTest {

    /** 测试用命令处理器：实现同一个接口，切点即按此接口的方法签名匹配。 */
    static class FakeHandler implements com.tg.heyisheng.bot.core.dispatch.CommandHandler {
        private final boolean shouldFail;

        FakeHandler(boolean shouldFail) {
            this.shouldFail = shouldFail;
        }

        @Override
        public org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod<?> handle(
                UpdateContext ctx) {
            if (shouldFail) {
                throw new IllegalStateException("boom");
            }
            return null;
        }
    }

    private static com.tg.heyisheng.bot.core.dispatch.CommandHandler proxied(
            FakeHandler target, AuditService audit) {
        org.springframework.aop.aspectj.annotation.AspectJProxyFactory factory =
                new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(target);
        factory.addAspect(new AuditAspect(audit));
        return factory.getProxy();
    }

    private static UpdateContext ctx() {
        return new UpdateContext(1, 777L, -100900999L, 5, "fake", null);
    }

    @Test
    void recordsSuccessForCommandHandler() throws Exception {
        AuditService audit = mock(AuditService.class);

        proxied(new FakeHandler(false), audit).handle(ctx());

        verify(audit).record(ActorType.TG_USER, 777L, "FakeHandler#handle", -100900999L,
                AuditEntry.Outcome.SUCCESS, null);
    }

    @Test
    void recordsFailureAndRethrows() {
        AuditService audit = mock(AuditService.class);
        com.tg.heyisheng.bot.core.dispatch.CommandHandler handler =
                proxied(new FakeHandler(true), audit);

        assertThatThrownBy(() -> handler.handle(ctx()))
                .as("审计不得吞掉业务异常——否则命令失败会被伪装成成功")
                .isInstanceOf(IllegalStateException.class);

        verify(audit).record(ActorType.TG_USER, 777L, "FakeHandler#handle", -100900999L,
                AuditEntry.Outcome.FAILURE, "IllegalStateException");
    }

    /** 断言切点确实按类型命中（而非恰好手写了这个类名）——不同类名的处理器同样被审。 */
    @Test
    void pointcutCoversOtherHandlerClasses() throws Exception {
        AuditService audit = mock(AuditService.class);
        org.springframework.aop.aspectj.annotation.AspectJProxyFactory factory =
                new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(new OtherHandler());
        factory.addAspect(new AuditAspect(audit));

        com.tg.heyisheng.bot.core.dispatch.CommandHandler handler = factory.getProxy();
        handler.handle(ctx());

        verify(audit).record(ActorType.TG_USER, 777L, "OtherHandler#handle", -100900999L,
                AuditEntry.Outcome.SUCCESS, null);
    }

    static class OtherHandler implements com.tg.heyisheng.bot.core.dispatch.CommandHandler {
        @Override
        public org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod<?> handle(
                UpdateContext ctx) {
            return null;
        }
    }
}

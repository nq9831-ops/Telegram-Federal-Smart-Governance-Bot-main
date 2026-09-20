package com.tg.heyisheng.bot.core.config;

import com.tg.heyisheng.bot.core.callback.CallbackHandler;
import com.tg.heyisheng.bot.core.callback.CallbackRouter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回调路由装配测试。
 *
 * <p><b>守什么</b>：路由必须**无条件**存在——它是机械的分派表，不属于任何功能开关。
 * 曾经它建在准入模块下（准入一关就全无路由）；交互卡片引入后，若把它建在交互开关下，
 * 又会反噬准入按钮。这两条都需要结构性护栏，而不是靠人记得。
 */
class CallbackConfigurationTest {

    private static final class FakeHandler implements CallbackHandler {
        private final String action;

        private FakeHandler(String action) {
            this.action = action;
        }

        @Override
        public String action() {
            return action;
        }

        @Override
        public Optional<BotApiMethod<?>> handle(CallbackQuery query) {
            return Optional.empty();
        }
    }

    /** 零处理器也要能装配——否则「没有任何可选回调」的环境会直接起不来。 */
    @Test
    void assemblesWithZeroHandlers() {
        new ApplicationContextRunner()
                .withUserConfiguration(CallbackConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(CallbackRouter.class).size()).isZero();
                });
    }

    /** 容器里注册了处理器就要被收进来（路由是「收集」，不需要谁显式登记）。 */
    @Test
    void collectsAllHandlerBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(CallbackConfiguration.class)
                .withBean("menu", CallbackHandler.class, () -> new FakeHandler("menu"))
                .withBean("cfm", CallbackHandler.class, () -> new FakeHandler("cfm"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(CallbackRouter.class).size()).isEqualTo(2);
                });
    }

    /**
     * 结构性护栏：{@code AdmissionConfiguration} **不得**再声明返回 {@code CallbackRouter} 的 bean。
     *
     * <p>理由是这个项目已经吃过一次同类型的亏：同类型 bean 重复 → 按类型注入的消费方
     * 以「expected single bean but found 2」让整个上下文起不来（那次是 ModerationActionSender）。
     * 用反射直接盯住「谁声明了路由」，比等集成测试报错更早、更准。
     */
    @Test
    void admissionConfigurationDoesNotDeclareRouter() {
        boolean declaresRouter = Arrays.stream(AdmissionConfiguration.class.getDeclaredMethods())
                .anyMatch(m -> m.getReturnType() == CallbackRouter.class);

        assertThat(declaresRouter)
                .as("路由归 CallbackConfiguration 无条件装配；AdmissionConfiguration 只贡献 verify 处理器")
                .isFalse();
    }
}

package com.tg.heyisheng.bot.core.config;

import com.tg.heyisheng.bot.core.callback.CallbackHandler;
import com.tg.heyisheng.bot.core.callback.CallbackRouter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 回调路由装配（**无条件**）。
 *
 * <p><b>为什么必须无条件</b>：路由本身只回答一个机械问题——「这个 action 前缀由谁处理」。
 * 它不属于任何功能开关：曾经它建在 {@code AdmissionConfiguration}（{@code tgg.admission.enabled}）里，
 * 结果是「准入一关，任何按钮回调都无路由」（见 KNOWN-ISSUES 的遗留项）。
 * 交互卡片引入后，同样的问题会反向重演——若把路由建在交互开关下，准入按钮又会失效。
 * 故路由独立于此，永不随功能开关消失。
 *
 * <p><b>处理器集合用 {@code ObjectProvider} 而非 {@code List} 注入</b>：后者在「零个候选」时的
 * 行为依赖注入点声明，历史上是打断单类 {@code ApplicationContextRunner} 的常见原因；
 * {@code ObjectProvider} 对「一个都没有」是明确的空流，行为可预期。
 */
@Configuration
public class CallbackConfiguration {

    /** 收集容器里全部 {@link CallbackHandler}；一个都没有时构造空路由（合法）。 */
    @Bean
    public CallbackRouter callbackRouter(ObjectProvider<CallbackHandler> handlers) {
        return new CallbackRouter(handlers.stream().toList());
    }
}

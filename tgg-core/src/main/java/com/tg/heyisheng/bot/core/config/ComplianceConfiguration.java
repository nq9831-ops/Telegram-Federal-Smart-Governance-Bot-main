package com.tg.heyisheng.bot.core.config;

import com.tg.heyisheng.bot.core.breach.DataBreachJob;
import com.tg.heyisheng.bot.core.breach.DataBreachRepository;
import com.tg.heyisheng.bot.core.breach.DataBreachService;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard;
import com.tg.heyisheng.bot.core.notify.NotificationDispatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 模块十 · 合规装配：数据泄露登记与 72 小时催办（§11.2）。
 *
 * <p><b>为什么从 {@code TggCoreConfiguration} 拆出来</b>：那一类曾装配 29 个 bean，
 * 覆盖六个互不相关的域；数据泄露通报是「合规」这一域的唯一落点，独立成类后
 * 「泄露通报怎么做」的阅读面从近 500 行收敛到本类。
 *
 * <p><b>拆分不影响装配</b>：{@code @Configuration} 类之间靠方法参数互相注入，
 * Spring 不关心某个 bean 定义在哪个类里（只要仍在组件扫描范围内）。
 * {@code @Scheduled} 的启用（{@code @EnableScheduling}）仍由 {@code TggCoreConfiguration} 承担
 * ——与本项目既有拆分（如 {@code NotificationConfiguration}）同一约定。
 */
@Configuration
public class ComplianceConfiguration {

    /**
     * 数据泄露登记与 72 小时催办（模块十 §11.2）。
     *
     * <p>不挂开关：泄露是合规事故，不该因为某个功能开关没打开就失去计时能力。
     * 催办用紧急级通知，不受用户免打扰影响。
     */
    @Bean
    public DataBreachService dataBreachService(DataBreachRepository dataBreachRepository) {
        return new DataBreachService(dataBreachRepository, Clock.systemUTC());
    }

    @Bean
    public DataBreachJob dataBreachJob(DataBreachService dataBreachService,
                                       ModerationReviewGuard moderationReviewGuard,
                                       NotificationDispatcher notificationDispatcher) {
        return new DataBreachJob(dataBreachService, moderationReviewGuard, notificationDispatcher);
    }
}

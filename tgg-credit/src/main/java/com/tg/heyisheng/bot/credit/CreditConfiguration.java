package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.util.IdHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模块七 · 信用分体系装配。
 *
 * <p><b>默认关闭</b>：与模块四（准入）、模块九（AI 层）同款——不设 {@code tgg.credit.enabled=true}
 * 时本类不产生任何 bean，{@code UpdateDispatcher} 走 {@code CreditEventSink.noop()}，
 * 审核与命令主链路零影响。
 *
 * <p><b>为什么集中装配而非散落 {@code @Component}</b>：信用分是"业务中枢"，但它读取的是
 * 审核判定等既有产物、并反过来施加处罚。让整组组件受同一个开关门控，
 * 才能保证"未启用 = 不产生任何影响"这一契约不被某个漏标的注解破坏
 * ——本项目在模块九踩过"漏装配即静默失效"的坑。
 */
@Configuration
@ConditionalOnProperty(prefix = "tgg.credit", name = "enabled", havingValue = "true")
public class CreditConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CreditConfiguration.class);

    /** 规则引擎：本阶段用内置常量规则，留 DB/按群配置的实现替换点。 */
    @Bean
    public CreditRuleEngine creditRuleEngine() {
        return new BuiltInCreditRules();
    }

    /** 信用记账服务。依赖 {@link IdHasher}（由核心装配无条件提供）做日志脱敏。 */
    @Bean
    public CreditService creditService(CreditRuleEngine creditRuleEngine,
                                       CreditScoreRepository creditScoreRepository,
                                       IdHasher idHasher) {
        log.info("模块七 · 信用分体系已启用（三套分：个人 / 群组 / 商家）。");
        return new CreditService(creditRuleEngine, creditScoreRepository, idHasher);
    }
}

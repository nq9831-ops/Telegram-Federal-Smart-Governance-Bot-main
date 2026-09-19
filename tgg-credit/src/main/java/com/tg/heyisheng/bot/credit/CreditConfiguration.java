package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.exception.TggConfigException;
import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.credit.CreditEventSink;
import com.tg.heyisheng.bot.core.wordfilter.TeachGate;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 *
 * <p><b>签名密钥缺失即启动失败</b>（fail-fast，与 AI 层缺 key 同款）：
 * 未签名的处罚令可被伪造，不能降级放行。
 */
@Configuration
@ConditionalOnProperty(prefix = "tgg.credit", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CreditProperties.class)
public class CreditConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CreditConfiguration.class);

    private final CreditProperties properties;

    public CreditConfiguration(CreditProperties properties) {
        this.properties = properties;
    }

    /** 在装配任何 bean 之前先校验密钥——避免"起了半个模块"才发现配置缺失。 */
    @PostConstruct
    void requirePrivateKey() {
        if (properties.getPrivateKey() == null || properties.getPrivateKey().isBlank()) {
            throw new TggConfigException(
                    "启用 tgg.credit.enabled 时必须配置 TGG_CREDIT_PRIVATE_KEY（Ed25519 私钥，PKCS#8 Base64；否则处罚令无法签名）");
        }
        log.info("模块七 · 信用分体系已启用（三套分：个人 / 群组 / 商家）。");
    }

    /** 规则引擎：本阶段用内置常量规则，留 DB/按群配置的实现替换点。 */
    @Bean
    public CreditRuleEngine creditRuleEngine() {
        return new BuiltInCreditRules();
    }

    /** 处罚令签名器（Ed25519，持本节点私钥）。 */
    @Bean
    public PenaltySigner penaltySigner() {
        return new PenaltySigner(properties.getPrivateKey());
    }

    /**
     * 处罚令发布通道。
     *
     * <p>本阶段为 noop——跨节点广播属模块八联邦治理（届时替换为真实实现，接口不变）。
     */
    @Bean
    public PenaltyOrderPublisher penaltyOrderPublisher() {
        return PenaltyOrderPublisher.noop();
    }

    /** 信用记账服务。依赖 {@link IdHasher}（由核心装配无条件提供）做日志脱敏。 */
    @Bean
    public CreditService creditService(CreditRuleEngine creditRuleEngine,
                                       CreditScoreRepository creditScoreRepository,
                                       CreditEventRecordRepository creditEventRecordRepository,
                                       IdHasher idHasher) {
        return new CreditService(creditRuleEngine, creditScoreRepository, creditEventRecordRepository, idHasher);
    }

    /**
     * 信用事件发布实现（{@code tgg-core} 的 {@link CreditEventSink} 契约）。
     *
     * <p>{@code UpdateDispatcher} 通过 {@code ObjectProvider<CreditEventSink>} 取它：
     * 未启用本模块时该 bean 不存在，调用方自动走 noop。
     */
    @Bean
    public CreditEventSink creditEventSink(CreditService creditService,
                                           PenaltySigner penaltySigner,
                                           PenaltyOrderPublisher penaltyOrderPublisher) {
        return new CreditEventSinkAdapter(creditService, penaltySigner, penaltyOrderPublisher);
    }

    /**
     * 「信用良好（无扣分）」教学门槛（模块九 §10.3）——注册 core 的 {@link TeachGate} 接缝，
     * 由 {@code TggCoreConfiguration} 的门槛聚合器自动并入判定。
     *
     * <p>随本类的开关一同门控：未启用模块七时该 bean 不存在，教学门槛里就没有「信用分」这一条
     * （core 的聚合器只看到「无违规」）——这是 {@code tgg.credit.enabled=false} 时
     * {@code /teach} 行为与升级前逐字一致的原因。
     */
    @Bean
    public TeachGate noDeductionTeachGate(CreditService creditService) {
        return new NoDeductionTeachGate(creditService);
    }
}

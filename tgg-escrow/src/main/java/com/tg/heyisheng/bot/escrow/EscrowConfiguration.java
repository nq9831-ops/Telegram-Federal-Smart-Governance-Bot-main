package com.tg.heyisheng.bot.escrow;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 模块十二 · 担保交易装配。
 *
 * <p><b>默认关闭</b>：不设 {@code tgg.escrow.enabled=true} 时本类不生效，整组组件不产生任何 bean，
 * 既有链路零影响。装配纪律同 {@code ListingConfiguration} / {@code MerchantConfiguration}——
 * 受门控的组件一律用 {@code @Bean} 集中收敛到本类，<b>不要</b>加 {@code @Component}。
 *
 * <p>本波只落骨架：配置 + 迁移（V24 建 {@code escrow_orders}）+ 订单状态机 / 争议裁决判定。
 * 命令入口（{@code /escrow_*}）与链上接入（gap-ESC-01）在后续波以 {@code @Bean} 形式补入。
 *
 * <p>时钟用系统 UTC，<b>不</b>额外注册 {@code Clock} bean——与 {@code ListingConfiguration} 同款，
 * 避免按类型注入歧义；测试的可注入性由 {@link EscrowService} 构造参数保证。
 */
@Configuration
@ConditionalOnProperty(prefix = "tgg.escrow", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(EscrowProperties.class)
public class EscrowConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EscrowConfiguration.class);

    private final EscrowProperties properties;

    public EscrowConfiguration(EscrowProperties properties) {
        this.properties = properties;
    }

    /** 启用播报（与模块六同款：门开时留一条可观测的日志）。 */
    @PostConstruct
    void logEnabledState() {
        log.info("模块十二 · 担保交易已启用：争议期={} 天、未锁仓超时={} 小时。"
                        + "链上接入（DepositGateway）留待后续波，本波只落本地账本与状态机。",
                properties.getDisputeWindowDays(), properties.getOrderTimeoutHours());
    }

    /**
     * 担保交易服务（订单状态机 + 争议裁决）。
     *
     * <p>{@link EscrowRepository} 由 Spring Data JPA 依 {@code @Entity} 自动装配（与模块五/六同款），
     * 仅当本 {@code @Configuration} 生效时才作为 {@code EscrowService} 的依赖被真正使用。
     */
    @Bean
    public EscrowService escrowService(EscrowRepository orders) {
        return new EscrowService(orders, properties, Clock.systemUTC());
    }
}

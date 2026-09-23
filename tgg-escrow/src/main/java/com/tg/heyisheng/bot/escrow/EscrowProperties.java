package com.tg.heyisheng.bot.escrow;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模块十二 · 担保交易配置（前缀 {@code tgg.escrow}）。
 *
 * <p>键通过环境变量注入生产值（Spring Boot 宽松绑定，多词键在 {@code application.yml} 里显式映射）：
 * <ul>
 *   <li>{@code TGG_ESCROW_ENABLED} → {@code tgg.escrow.enabled}（默认 false；不启用则整组组件不装配）</li>
 *   <li>{@code TGG_ESCROW_DISPUTE_WINDOW_DAYS} → {@code tgg.escrow.dispute-window-days}（默认 7）</li>
 *   <li>{@code TGG_ESCROW_ORDER_TIMEOUT_HOURS} → {@code tgg.escrow.order-timeout-hours}（默认 72）</li>
 * </ul>
 *
 * <p>本类由 {@code EscrowConfiguration} 经 {@code @EnableConfigurationProperties} 绑定，
 * 属<b>启动期</b>读取——{@code ConfigCatalog} 中相应键一律标「改动需重启」，不做热生效的虚假承诺。
 */
@ConfigurationProperties(prefix = "tgg.escrow")
public class EscrowProperties {

    /** 是否启用模块十二（默认 false；不启用时整个模块不产生任何 bean）。 */
    private boolean enabled = false;

    /** 争议期（自订单创建起算，天）。 */
    private int disputeWindowDays = 7;

    /** 订单未锁仓超时（自创建起算，小时）。 */
    private int orderTimeoutHours = 72;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDisputeWindowDays() {
        return disputeWindowDays;
    }

    public void setDisputeWindowDays(int disputeWindowDays) {
        this.disputeWindowDays = disputeWindowDays;
    }

    public int getOrderTimeoutHours() {
        return orderTimeoutHours;
    }

    public void setOrderTimeoutHours(int orderTimeoutHours) {
        this.orderTimeoutHours = orderTimeoutHours;
    }
}

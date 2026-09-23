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
 *   <li>{@code TGG_ESCROW_NOTIFY_GROUP_ENABLED} → {@code tgg.escrow.notify.group-enabled}（默认 true）</li>
 *   <li>{@code TGG_ESCROW_NOTIFY_PRIVATE_ENABLED} → {@code tgg.escrow.notify.private-enabled}（默认 true）</li>
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

    /** 通知通道开关（群内 / 私聊各一枚，可分别关闭）。 */
    private Notify notify = new Notify();

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

    public Notify getNotify() {
        return notify;
    }

    public void setNotify(Notify notify) {
        this.notify = notify;
    }

    /**
     * 通知通道开关：<b>群内与私聊都要有，但要有开关</b>（用户明确要求）。
     *
     * <p>两条通道的职责不同，故分开控制：
     * <ul>
     *   <li><b>群内</b>：把进度发到交易所在群——公开可见、便于双方与围观者对齐；</li>
     *   <li><b>私聊</b>：确保对方收到（对方可能根本不在那个群里）。</li>
     * </ul>
     * 两个都关 = 只剩命令回执（调用方自己看得见），交易对他方静默——运维需自知这一点。
     */
    public static class Notify {

        /** 群内通道开关（默认开）。 */
        private boolean groupEnabled = true;

        /** 私聊通道开关（默认开）。 */
        private boolean privateEnabled = true;

        public boolean isGroupEnabled() {
            return groupEnabled;
        }

        public void setGroupEnabled(boolean groupEnabled) {
            this.groupEnabled = groupEnabled;
        }

        public boolean isPrivateEnabled() {
            return privateEnabled;
        }

        public void setPrivateEnabled(boolean privateEnabled) {
            this.privateEnabled = privateEnabled;
        }
    }
}

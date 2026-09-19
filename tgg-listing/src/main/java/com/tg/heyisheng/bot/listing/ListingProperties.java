package com.tg.heyisheng.bot.listing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模块五 · 群组收录配置（前缀 {@code tgg.listing}）。
 *
 * <p>键通过环境变量注入生产值（Spring Boot 宽松绑定）：
 * <ul>
 *   <li>{@code TGG_LISTING_ENABLED} → {@code tgg.listing.enabled}（默认 false；不启用则整组组件不装配）</li>
 *   <li>{@code TGG_LISTING_VERIFY_CRON} → {@code tgg.listing.verify-cron}（默认 {@code 0 0 3 * * *}）</li>
 *   <li>{@code TGG_LISTING_FAIL_THRESHOLD} → {@code tgg.listing.fail-threshold}（默认 3）</li>
 *   <li>{@code TGG_LISTING_RETRY_TIMES} → {@code tgg.listing.retry-times}（默认 2）</li>
 *   <li>{@code TGG_LISTING_RETRY_INTERVAL_MINUTES} → {@code tgg.listing.retry-interval-minutes}（默认 5）</li>
 *   <li>{@code TGG_LISTING_DISPUTE_WINDOW_DAYS} → {@code tgg.listing.dispute-window-days}（默认 7）</li>
 * </ul>
 *
 * <p><b>注意变量名映射</b>：多词键（如 {@code verify-cron}）的规范环境变量名会去掉连字符
 * （{@code TGG_LISTING_VERIFYCRON}），与文档常用的下划线写法不一致；与模块七
 * {@code tgg.credit.private-key} 同款处理——生产启用时在 {@code application.yml} 显式写
 * {@code verify-cron: ${TGG_LISTING_VERIFY_CRON:0 0 3 * * *}}，避免绑定落空。
 */
@ConfigurationProperties(prefix = "tgg.listing")
public class ListingProperties {

    /** 是否启用模块五（默认 false；不启用时整个模块不产生任何 bean）。 */
    private boolean enabled = false;

    /** 失效验证任务 cron（默认每日 03:00）。 */
    private String verifyCron = "0 0 3 * * *";

    /** 连续失败判失效的阈值（V5.0：3）。 */
    private int failThreshold = 3;

    /** 单次验证的失败重试次数（V5.0：2）。 */
    private int retryTimes = 2;

    /** 重试间隔（分钟，V5.0：5）。 */
    private int retryIntervalMinutes = 5;

    /** 失效异议期（天，V5.0：7）。 */
    private int disputeWindowDays = 7;

    /**
     * 「长期未成功验证」告警阈值（天，默认 3；**本项目新增，非 V5.0 规格**）。
     *
     * <p>用途：某条 ACTIVE 收录超过该天数没有**验证成功**过（含从未成功），说明探测层可能持续不可用
     * （缺 {@code TGG_BOT_TOKEN} / 网络不通 / 被限流）——这种情况**不会**触发任何既有流程，
     * 因为 {@code ERROR} 既不累加 {@code failCount} 也不改变状态。只告警，不改变任何状态。
     */
    private int staleVerifyDays = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getVerifyCron() {
        return verifyCron;
    }

    public void setVerifyCron(String verifyCron) {
        this.verifyCron = verifyCron;
    }

    public int getFailThreshold() {
        return failThreshold;
    }

    public void setFailThreshold(int failThreshold) {
        this.failThreshold = failThreshold;
    }

    public int getRetryTimes() {
        return retryTimes;
    }

    public void setRetryTimes(int retryTimes) {
        this.retryTimes = retryTimes;
    }

    public int getRetryIntervalMinutes() {
        return retryIntervalMinutes;
    }

    public void setRetryIntervalMinutes(int retryIntervalMinutes) {
        this.retryIntervalMinutes = retryIntervalMinutes;
    }

    public int getDisputeWindowDays() {
        return disputeWindowDays;
    }

    public void setDisputeWindowDays(int disputeWindowDays) {
        this.disputeWindowDays = disputeWindowDays;
    }

    public int getStaleVerifyDays() {
        return staleVerifyDays;
    }

    public void setStaleVerifyDays(int staleVerifyDays) {
        this.staleVerifyDays = staleVerifyDays;
    }
}

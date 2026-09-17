package com.tg.heyisheng.bot.core.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据保留策略配置（模块十 §11.2）。
 *
 * <p><b>默认关闭</b>：保留策略的本质是<b>自动删除业务数据</b>，属高危动作。
 * 默认只做「报告」——列出哪些数据已超期、各多少行；只有显式开启才会真删。
 * 这与本项目对破坏性操作的一贯口径一致。
 *
 * <p><b>审计表不在此列，且不可配置</b>：{@code audit_log} 的「不可删」是上一波立的规矩，
 * 保留策略若把它纳入就地自我否定。排除逻辑硬编码在 {@link RetentionService}，不是配置项。
 */
@ConfigurationProperties(prefix = "tgg.retention")
public class RetentionProperties {

    /** 是否真的执行清理。{@code false}（默认）= 只报告。 */
    private boolean enabled = false;

    /** 已裁决的复核队列条目保留天数（超过即可清理）。 */
    private int reviewedQueueDays = 90;

    /** 敏感话题累计计数的保留天数（超过即清零——「屡犯升级」需要窗口，不能永久累计）。 */
    private int strikeDays = 365;

    /** 每日清理时间（cron）。默认凌晨 4:30，避开收录验证（3:00）。 */
    private String cron = "0 30 4 * * *";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getReviewedQueueDays() {
        return reviewedQueueDays;
    }

    public void setReviewedQueueDays(int reviewedQueueDays) {
        this.reviewedQueueDays = reviewedQueueDays;
    }

    public int getStrikeDays() {
        return strikeDays;
    }

    public void setStrikeDays(int strikeDays) {
        this.strikeDays = strikeDays;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }
}

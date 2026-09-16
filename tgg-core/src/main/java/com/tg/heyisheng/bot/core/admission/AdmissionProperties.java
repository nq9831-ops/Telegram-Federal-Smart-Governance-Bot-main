package com.tg.heyisheng.bot.core.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 准入与验证的配置。
 *
 * <p><b>默认关闭</b>（{@code tgg.admission.enabled=false}）——与 failover 同风格：
 * 入群验证会改变群里每个新成员的体验，不该在没人显式开启时自动生效。
 *
 * <p>⚠️ **部署时必须显式设置**，否则该能力完全不装配且不报错——这类"看起来正常但从不生效"
 * 的静默降级只能靠部署文档提醒（见 docs/DEPLOYMENT-VERIFICATION.md）。
 */
@ConfigurationProperties(prefix = "tgg.admission")
public class AdmissionProperties {

    /** 是否启用入群验证。 */
    private boolean enabled = false;

    /** 验证时限（秒）：新成员须在此时间内点击验证，否则被移出。 */
    private int timeoutSeconds = 120;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}

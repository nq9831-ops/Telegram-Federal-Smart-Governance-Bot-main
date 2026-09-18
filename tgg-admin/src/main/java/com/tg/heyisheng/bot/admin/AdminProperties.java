package com.tg.heyisheng.bot.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模块十一（Web 后台）配置。
 *
 * <p><b>默认不装配</b>：{@code tgg.admin.api-token} 为空时整个模块的端点不产生
 * （见 {@link AdminConfiguration} 的条件），与项目「未启用即零影响」的契约一致——
 * 一个连 token 都没配的后台端点，不该在公网上存在。
 */
@ConfigurationProperties(prefix = "tgg.admin")
public class AdminProperties {

    /** API 访问令牌（请求头 {@code Authorization: Bearer <token>}）。空 = 端点不装配。 */
    private String apiToken = "";

    /** 待办超过该小时数 → 提醒（模块十一 §12.1 第 4 步）。 */
    private int overdueRemindHours = 24;

    /** 待办超过该小时数 → 升级提醒（同上）。 */
    private int overdueEscalateHours = 72;

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public int getOverdueRemindHours() {
        return overdueRemindHours;
    }

    public void setOverdueRemindHours(int overdueRemindHours) {
        this.overdueRemindHours = overdueRemindHours;
    }

    public int getOverdueEscalateHours() {
        return overdueEscalateHours;
    }

    public void setOverdueEscalateHours(int overdueEscalateHours) {
        this.overdueEscalateHours = overdueEscalateHours;
    }
}

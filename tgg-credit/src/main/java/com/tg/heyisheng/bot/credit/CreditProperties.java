package com.tg.heyisheng.bot.credit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模块七配置（前缀 {@code tgg.credit}）。
 *
 * <p>两个键都通过环境变量注入生产值：
 * <ul>
 *   <li>{@code TGG_CREDIT_ENABLED} → {@code tgg.credit.enabled}（默认 false）</li>
 *   <li>{@code TGG_CREDIT_SIGNING_KEY} → {@code tgg.credit.signing-key}（启用时必填）</li>
 * </ul>
 *
 * <p><b>注意变量名映射</b>：Spring 默认会把 {@code tgg.credit.signingKey} 推导为
 * {@code TGG_CREDIT_SIGNINGKEY}（无下划线），故本项目在 {@code application.yml} 显式写
 * {@code signing-key: ${TGG_CREDIT_SIGNING_KEY:}}——与既有 {@code TGG_DEEPSEEK_API_KEY} 同款处理。
 */
@ConfigurationProperties(prefix = "tgg.credit")
public class CreditProperties {

    /** 是否启用模块七（默认 false；不启用时整个模块不产生任何 bean）。 */
    private boolean enabled;

    /** 处罚令签名密钥（启用时必填）。 */
    private String signingKey;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSigningKey() {
        return signingKey;
    }

    public void setSigningKey(String signingKey) {
        this.signingKey = signingKey;
    }
}

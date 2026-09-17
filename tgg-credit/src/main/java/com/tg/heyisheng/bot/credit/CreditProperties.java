package com.tg.heyisheng.bot.credit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模块七配置（前缀 {@code tgg.credit}）。
 *
 * <p>键通过环境变量注入生产值：
 * <ul>
 *   <li>{@code TGG_CREDIT_ENABLED} → {@code tgg.credit.enabled}（默认 false）</li>
 *   <li>{@code TGG_CREDIT_PRIVATE_KEY} → {@code tgg.credit.private-key}（启用时必填）</li>
 * </ul>
 *
 * <p><b>2026-09-17 变更</b>：原 {@code signing-key}（HMAC 共享串）改为 {@code private-key}
 * （Ed25519 PKCS#8 Base64 私钥）——对称签名做不到"节点间不可互相伪造"，模块八联邦要求每节点独立密钥。
 *
 * <p><b>注意变量名映射</b>：Spring 默认会把 {@code tgg.credit.privateKey} 推导为
 * {@code TGG_CREDIT_PRIVATEKEY}（无下划线），故 {@code application.yml} 显式写
 * {@code private-key: ${TGG_CREDIT_PRIVATE_KEY:}}——与既有 {@code TGG_DEEPSEEK_API_KEY} 同款处理。
 */
@ConfigurationProperties(prefix = "tgg.credit")
public class CreditProperties {

    /** 是否启用模块七（默认 false；不启用时整个模块不产生任何 bean）。 */
    private boolean enabled;

    /** 本节点处罚令签名私钥（Ed25519 PKCS#8 Base64；启用时必填）。 */
    private String privateKey;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }
}

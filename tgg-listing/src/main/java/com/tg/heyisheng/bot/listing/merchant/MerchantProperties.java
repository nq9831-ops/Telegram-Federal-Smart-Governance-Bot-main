package com.tg.heyisheng.bot.listing.merchant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模块六 · 商家收录配置（前缀 {@code tgg.merchant}）。
 *
 * <p>键通过环境变量注入生产值（Spring Boot 宽松绑定）：
 * <ul>
 *   <li>{@code TGG_MERCHANT_ENABLED} → {@code tgg.merchant.enabled}（默认 false；不启用则整组组件不装配）</li>
 *   <li>{@code TGG_MERCHANT_INITIAL_SCORE} → {@code tgg.merchant.initial-score}（默认 500）</li>
 * </ul>
 *
 * <p><b>原 {@code TGG_MERCHANT_REVIEWERS}（资质复核人白名单）已随授权改判裁撤</b>
 * （2026-09-22 二次拍板：「商家只有联邦管理员才可以审核处理」）——商家管理命令的授权源是
 * {@code tgg.federation.admins} / 平台 {@code FEDERATION_ADMIN}（{@code FederationAdminGuard}，
 * core.platform 恒在装配）。该环境变量残留于部署环境时无害无效果。
 *
 * <p><b>与模块七的关系</b>：{@code initial-score} 只在商家入驻成功时通过模块七的
 * 「显式初始化分值」入口写入 {@code CreditSubjectType.MERCHANT} 的账本行，
 * <b>不改</b>模块七 {@code INITIAL_SCORE} 的既有默认值。
 */
@ConfigurationProperties(prefix = "tgg.merchant")
public class MerchantProperties {

    /** 是否启用模块六（默认 false；不启用时整个模块不产生任何 bean）。 */
    private boolean enabled = false;

    /** 商家初始信用分（V5.0 §7.1：500）。 */
    private int initialScore = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getInitialScore() {
        return initialScore;
    }

    public void setInitialScore(int initialScore) {
        this.initialScore = initialScore;
    }
}

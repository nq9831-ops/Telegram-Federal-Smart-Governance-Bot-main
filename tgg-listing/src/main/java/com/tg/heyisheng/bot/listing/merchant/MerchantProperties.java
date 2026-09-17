package com.tg.heyisheng.bot.listing.merchant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模块六 · 商家收录配置（前缀 {@code tgg.merchant}）。
 *
 * <p>键通过环境变量注入生产值（Spring Boot 宽松绑定）：
 * <ul>
 *   <li>{@code TGG_MERCHANT_ENABLED} → {@code tgg.merchant.enabled}（默认 false；不启用则整组组件不装配）</li>
 *   <li>{@code TGG_MERCHANT_INITIAL_SCORE} → {@code tgg.merchant.initial-score}（默认 500）</li>
 *   <li>{@code TGG_MERCHANT_REVIEWERS} → {@code tgg.merchant.reviewers}（资质复核人 user id，逗号分隔；空则启动 WARN）</li>
 * </ul>
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

    /** 资质复核人 user id 列表（逗号分隔；空则资质复核命令对任何人不可用）。 */
    private String reviewers;

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

    public String getReviewers() {
        return reviewers;
    }

    public void setReviewers(String reviewers) {
        this.reviewers = reviewers;
    }
}

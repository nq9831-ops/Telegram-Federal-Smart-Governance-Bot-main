package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.common.exception.TggConfigException;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

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

    /**
     * 解析复核人清单（逗号分隔的 user id）。
     *
     * <p>照 {@code FederationProperties.parsedAdmins()} 的口径：非数字条目<b>启动期即抛</b>
     * ——配置错误应在启动时暴露，而不是在运行期把一个复核人静默漏掉
     * （「空配置即静默失效」是本项目反复踩过的坑）。
     *
     * <p><b>为什么是独立的全局白名单而非群内 RBAC</b>：商家复核是<b>平台层</b>动作
     * （商家跨群，不属于任何一个群），而 {@code Role} 是<b>群内</b>权能模型
     * （{@code <chatId>:<userId>[:role]}）。硬塞进群内模型会让授权源的 {@code chatId}
     * 语义错配——与模块八 {@code FederationAdminGuard} 的取舍一致。
     */
    public List<Long> parsedReviewers() {
        if (reviewers == null || reviewers.isBlank()) {
            return List.of();
        }
        return Arrays.stream(reviewers.split(","))
                .map(String::trim)
                .filter(spec -> !spec.isEmpty())
                .map(spec -> {
                    try {
                        return Long.parseLong(spec);
                    } catch (NumberFormatException ex) {
                        throw new TggConfigException("tgg.merchant.reviewers 含非数字条目：" + spec, ex);
                    }
                })
                .toList();
    }
}

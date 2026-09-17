package com.tg.heyisheng.bot.listing.merchant;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 模块六 · 商家收录装配。
 *
 * <p><b>默认关闭</b>：不设 {@code tgg.merchant.enabled=true} 时本类不生效，整组组件不产生任何
 * bean，既有链路零影响。装配纪律同 {@code ListingConfiguration}——受门控的组件一律用
 * {@code @Bean} 集中收敛到本类，<b>不要</b>加 {@code @Component}。
 *
 * <p>本波（Wave 1）只落骨架：配置 + 迁移。入驻 / 复核 / 保证金等受门控服务在后续波以
 * {@code @Bean} 形式补入。
 */
@Configuration
@ConditionalOnProperty(prefix = "tgg.merchant", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MerchantProperties.class)
public class MerchantConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MerchantConfiguration.class);

    private final MerchantProperties properties;

    public MerchantConfiguration(MerchantProperties properties) {
        this.properties = properties;
    }

    /** 复核人清单为空时给出 WARN（同 {@code permission.admins} 纪律：空配置不得静默失效）。 */
    @PostConstruct
    void warnOnEmptyReviewers() {
        if (properties.getReviewers() == null || properties.getReviewers().isBlank()) {
            log.warn("未配置 tgg.merchant.reviewers（TGG_MERCHANT_REVIEWERS）：资质复核命令对任何人不可用。");
        } else {
            log.info("模块六 · 商家收录已启用：初始信用分={}。", properties.getInitialScore());
        }
    }
}

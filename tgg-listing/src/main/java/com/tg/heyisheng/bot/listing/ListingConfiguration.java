package com.tg.heyisheng.bot.listing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 模块五 · 群组收录装配。
 *
 * <p><b>默认关闭</b>（与模块七/八一致）：不设 {@code tgg.listing.enabled=true} 时本类不生效，
 * 整组组件不产生任何 bean，既有链路零影响。
 *
 * <p><b>为什么集中装配而非散落 {@code @Component}</b>：收录库、验证任务、命令处理器都受同一开关
 * 门控——只有把受门控的组件一律收敛到本类的 {@code @Bean} 方法里，才能保证「未启用 = 不产生任何
 * 影响」这一契约不被某个漏标的注解破坏（本项目在模块九踩过「漏装配即静默失效」的坑，
 * 见 LESSONS 的集中装配纪律）。
 *
 * <p>本波（Wave 1）只落骨架：实体 / 仓库 / 配置 / 迁移。受门控的服务（验证任务、命令处理器、
 * 通知器）在后续波以 {@code @Bean} 形式补入本类——<b>不要</b>给它们加 {@code @Component}。
 */
@Configuration
@ConditionalOnProperty(prefix = "tgg.listing", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ListingProperties.class)
public class ListingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ListingConfiguration.class);

    private final ListingProperties properties;

    public ListingConfiguration(ListingProperties properties) {
        this.properties = properties;
    }

    /** 记录启用事实与生效参数，便于运维核对（本波无受门控 bean，后续波在此集中 @Bean 装配）。 */
    @PostConstruct
    void logEnabled() {
        log.info("模块五 · 群组收录已启用：验证 cron={}，失败阈值={}，重试={} 次 / 间隔 {} 分钟，异议期 {} 天。",
                properties.getVerifyCron(), properties.getFailThreshold(),
                properties.getRetryTimes(), properties.getRetryIntervalMinutes(),
                properties.getDisputeWindowDays());
    }
}

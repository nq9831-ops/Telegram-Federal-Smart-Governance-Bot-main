package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.credit.CreditService;
import com.tg.heyisheng.bot.core.interaction.MenuVisibility;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 模块六 · 商家收录装配。
 *
 * <p><b>默认关闭</b>：不设 {@code tgg.merchant.enabled=true} 时本类不生效，整组组件不产生任何
 * bean，既有链路零影响。装配纪律同 {@code ListingConfiguration}——受门控的组件一律用
 * {@code @Bean} 集中收敛到本类，<b>不要</b>加 {@code @Component}。
 *
 * <p>Waves 1–3 只落骨架：配置 + 迁移（V6 已建 {@code merchants} 等表）。Wave 4 起补入驻服务
 * 与复核人判定；保证金（Wave 5）与等级评定（Wave 6）在后续波以 {@code @Bean} 形式补入。
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

    /**
     * 资质复核人判定（全局白名单，{@code tgg.merchant.reviewers}）。
     *
     * <p>非数字条目会在 {@code parsedReviewers()} 里于<b>装配期</b>抛出——配置错误应在启动时
     * 暴露，而非运行期把某个复核人静默漏掉。
     */
    @Bean
    public MerchantReviewGuard merchantReviewGuard(
            com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService runtimeConfig,
            org.springframework.beans.factory.ObjectProvider<com.tg.heyisheng.bot.core.platform.PlatformGrantSource> platformGrants) {
        // ObjectProvider：切片上下文可能不含 core 的账本 bean——缺失时回落配置键
        return new MerchantReviewGuard(runtimeConfig, platformGrants.getIfAvailable());
    }

    /**
     * {@code /menu} 的「收录商家」可见性接缝：资质复核类命令走全局白名单，注册表判不出可见性，
     * 故把判定交给菜单侧——详见 {@link MerchantMenuVisibility}。
     *
     * <p>随本类的 {@code tgg.merchant.enabled} 门控一同出现/消失：模块未启用时连命令都不存在，
     * 菜单里自然不该有「收录商家」分类。
     */
    @Bean
    public MenuVisibility merchantMenuVisibility(MerchantReviewGuard merchantReviewGuard) {
        return new MerchantMenuVisibility(merchantReviewGuard);
    }

    /**
     * 商家入驻服务（状态机）。
     *
     * <p>{@link CreditService} 经 {@link ObjectProvider} 取：模块六与模块七是两个<b>独立开关</b>，
     * 信用模块未启用时返回 {@code null}，入驻照常可完成、信用分初始化记 WARN 跳过
     * （降级可观测，而非静默漏写，也不会让上下文起不来）。
     *
     * <p>时钟用系统 UTC，<b>不</b>额外注册 {@code Clock} bean——与 {@code ListingConfiguration} 同款，
     * 避免按类型注入歧义；测试的可注入性由构造参数保证。
     */
    @Bean
    public MerchantService merchantService(MerchantRepository merchants,
                                           ObjectProvider<CreditService> creditService,
                                           com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService runtimeConfig) {
        return new MerchantService(merchants, properties, creditService.getIfAvailable(),
                Clock.systemUTC(), runtimeConfig);
    }

    /**
     * 保证金链上动作接入位（设计文档 §3.1）。
     *
     * <p>默认 {@link NoopDepositGateway}：<b>账本 / 状态机 / 流水 / 退还三分支全部真实工作</b>，
     * 只有链上那一半是本地的（每次都打 WARN，避免运维误以为已上链）。真实 ton4j 实现由部署方
     * 以 {@code @Primary} 覆盖本 bean——接口不变，模块十二（担保交易）届时可直接对接。
     */
    @Bean
    public DepositGateway depositGateway() {
        return new NoopDepositGateway();
    }

    /**
     * 保证金服务（状态机 + 退还三分支）。
     *
     * <p>它持有 {@link MerchantService} 做编排：「缴费开始」推商家到 {@code DEPOSIT_PENDING}，
     * 「锁仓成功」推商家到 {@code ACTIVE}（含信用分初始化）——Wave 4 留下的
     * {@code markDepositPending} / {@code markActive} 在此获得生产调用方。
     */
    @Bean
    public MerchantDepositService merchantDepositService(MerchantDepositRepository deposits,
                                                         MerchantDepositRecordRepository records,
                                                         MerchantService merchantService,
                                                         DepositGateway depositGateway) {
        return new MerchantDepositService(deposits, records, merchantService, depositGateway,
                Clock.systemUTC());
    }

    /*
     * 命令处理器（/merchant_apply、/merchant_review、/merchant_status）**刻意不在这里 @Bean 装配**：
     * 注册命令所需的 @BotCommand 注解本身元注解了 @Component，标注它的类必然进入组件扫描，
     * 因此再在此处 @Bean 一次就会产生两个同类型实例 → CommandRegistry 会以「命令名冲突」
     * 直接让上下文档启动失败。解法与 ListingAddCommandHandler / 模块八 AppealCommandHandler 一致：
     * 命令类自持 @ConditionalOnProperty(tgg.merchant.enabled) 门控，门开才装配、门关一个都不产生。
     */
}

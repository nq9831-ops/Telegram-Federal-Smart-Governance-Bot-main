package com.tg.heyisheng.bot.listing;

import com.tg.heyisheng.bot.listing.verification.GroupLinkVerificationJob;
import com.tg.heyisheng.bot.listing.verification.GroupLinkVerifier;
import com.tg.heyisheng.bot.listing.verification.Sleeper;
import com.tg.heyisheng.bot.listing.verification.TelegramGroupLinkVerifier;
import com.tg.heyisheng.bot.listing.verification.VerificationRecordRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

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
 * <p>{@code @EnableScheduling} 与本类同受门控（同 {@code FailoverConfiguration} 范式）：
 * 只有启用模块五才注册调度器，验证任务绝不会在关闭状态下被触发。
 */
@Configuration
@ConditionalOnProperty(prefix = "tgg.listing", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ListingProperties.class)
@EnableScheduling
public class ListingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ListingConfiguration.class);

    private final ListingProperties properties;

    public ListingConfiguration(ListingProperties properties) {
        this.properties = properties;
    }

    /** 记录启用事实与生效参数，便于运维核对。 */
    @PostConstruct
    void logEnabled() {
        log.info("模块五 · 群组收录已启用：验证 cron={}，失败阈值={}，重试={} 次 / 间隔 {} 分钟，异议期 {} 天。",
                properties.getVerifyCron(), properties.getFailThreshold(),
                properties.getRetryTimes(), properties.getRetryIntervalMinutes(),
                properties.getDisputeWindowDays());
    }

    /**
     * 群链接探针（可替换接缝）。
     *
     * <p>bot token 复用既有 {@code tgg.webhook.bot-token}（同一个 bot，不引第二套凭据）；
     * 默认值为空串——本机/未配置时探针恒返回 {@code ERROR}（探测不可用 ≠ 群失效），
     * 真实探测能力属部署后验证项。
     */
    @Bean
    public GroupLinkVerifier groupLinkVerifier(@Value("${tgg.webhook.bot-token:}") String botToken) {
        if (botToken == null || botToken.isBlank()) {
            log.warn("未配置 TGG_BOT_TOKEN：群链接探针在运行期只会产出 ERROR（不会误判失效），"
                    + "收录验证的「真失效判定」需部署方注入 token 后才生效。");
        }
        return new TelegramGroupLinkVerifier(botToken);
    }

    /**
     * 重试等待接缝：生产为真实睡眠。
     *
     * <p>独立成 bean 是为了让测试注入「只记录调用」的替身——否则一条断言就要真等 5 分钟。
     */
    @Bean
    public Sleeper listingRetrySleeper() {
        return Sleeper.threadSleep();
    }

    /**
     * 收录库服务（状态机）。
     *
     * <p>时钟用系统 UTC，<b>不</b>额外注册 {@code Clock} bean——避免与其它模块潜在的
     * {@code Clock} bean 造成按类型注入歧义；测试的可注入性由构造参数保证
     * （直接 {@code new ListingGroupService(..., Clock.fixed(...))}）。
     */
    @Bean
    public ListingGroupService listingGroupService(ListingGroupRepository listingGroupRepository,
                                                   VerificationRecordRepository verificationRecordRepository,
                                                   GroupLinkVerifier groupLinkVerifier) {
        return new ListingGroupService(listingGroupRepository, verificationRecordRepository,
                groupLinkVerifier, properties, Clock.systemUTC());
    }

    /** 每日凌晨的链接验证任务。 */
    @Bean
    public GroupLinkVerificationJob groupLinkVerificationJob(ListingGroupService listingGroupService,
                                                             Sleeper listingRetrySleeper) {
        return new GroupLinkVerificationJob(listingGroupService, properties, listingRetrySleeper);
    }
}

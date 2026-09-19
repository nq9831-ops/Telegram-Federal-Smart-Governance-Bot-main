package com.tg.heyisheng.bot.listing;

import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import com.tg.heyisheng.bot.listing.notify.LoggingSubmitterNotifier;
import com.tg.heyisheng.bot.listing.notify.SubmitterNotifier;
import com.tg.heyisheng.bot.listing.notify.TelegramSubmitterNotifier;
import com.tg.heyisheng.bot.listing.verification.GroupLinkVerificationJob;
import com.tg.heyisheng.bot.listing.verification.GroupLinkVerifier;
import com.tg.heyisheng.bot.listing.verification.Sleeper;
import com.tg.heyisheng.bot.listing.verification.TelegramGroupLinkVerifier;
import com.tg.heyisheng.bot.listing.verification.VerificationRecordRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    public GroupLinkVerifier groupLinkVerifier(WebhookProperties webhookProperties) {
        String botToken = webhookProperties.getBotToken();
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

    /**
     * 下架通知通道（设计文档 §6.4）：<b>按 bot token 是否存在分流</b>。
     *
     * <p>有 token → {@link TelegramSubmitterNotifier}（Bot API {@code sendMessage} 私聊提交者，附申诉指引）；
     * 无 token → {@link LoggingSubmitterNotifier}（只记日志）并打 <b>WARN</b>。
     *
     * <p><b>为什么不干脆只留日志实现</b>：那样等于「不论怎么配，提交者都收不到告知」
     * ——同一项目里硬红线封禁能真发、而通知永远发不出，这个不对称本身就是缺口。
     * 真投递在此落地后，部署方只需按 README 注入 {@code TGG_BOT_TOKEN} 即可获得完整闭环
     * （下架 → 告知 → 申诉入口）。走自建网关/代理时替换本 bean 即可（接口不变，
     * 契约仍是「实现必须自行吞异常」）。
     *
     * <p><b>降级是显式的</b>：无 token 时不是静默退回日志，而是启动期就 WARN 说明
     * 「提交者不会收到任何告知」，避免运维误以为通知已生效。
     */
    @Bean
    public SubmitterNotifier submitterNotifier(WebhookProperties webhookProperties) {
        String botToken = webhookProperties.getBotToken();
        if (botToken == null || botToken.isBlank()) {
            log.warn("未配置 TGG_BOT_TOKEN：下架通知退化为日志实现——提交者不会收到任何告知；"
                    + "注入 token 后自动切换为 Bot API 真实投递（需提交者曾与 bot 私聊过）。");
            return new LoggingSubmitterNotifier();
        }
        return new TelegramSubmitterNotifier(botToken);
    }

    /** 每日凌晨的链接验证任务（判失效时经 {@link SubmitterNotifier} 通知提交者）。 */
    @Bean
    public GroupLinkVerificationJob groupLinkVerificationJob(ListingGroupService listingGroupService,
                                                             Sleeper listingRetrySleeper,
                                                             SubmitterNotifier submitterNotifier) {
        return new GroupLinkVerificationJob(listingGroupService, properties, listingRetrySleeper,
                submitterNotifier);
    }

    /*
     * 命令处理器（/listing_add、/listing_list、/listing_appeal）**刻意不在这里 @Bean 装配**：
     * 注册命令所需的 @BotCommand 注解本身元注解了 @Component，标注它的类必然进入组件扫描，
     * 因此再在此处 @Bean 一次就会产生两个同类型实例 → CommandRegistry 会以「命令名冲突」
     * 直接让上下文档启动失败。解法与模块八 AppealCommandHandler 一致：命令类自持
     * @ConditionalOnProperty(tgg.listing.enabled) 门控，门开才装配、门关一个都不产生。
     */
}

package com.tg.heyisheng.bot.federation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.common.exception.TggConfigException;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigRepository;
import com.tg.heyisheng.bot.core.interaction.MenuVisibility;
import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import com.tg.heyisheng.bot.credit.PenaltyOrderPublisher;
import jakarta.annotation.PostConstruct;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 模块八 · 联邦治理装配。
 *
 * <p><b>默认关闭</b>（与模块四/七/九一致）：不设 {@code tgg.federation.enabled=true} 时
 * 本类不产生任何 bean，{@code updateDispatcher} 走模块七的 {@code PenaltyOrderPublisher.noop()}，
 * 既有链路零影响。
 *
 * <p><b>节点清单为空即启动失败</b>（用户批注）：启用联邦却没有任何对端，是明确的配置错误——
 * 静默允许会表现为"联邦开了但从不广播"（本项目反复踩的"空配置即静默失效"坑）。
 *
 * <p><b>出站广播以 {@code @Primary} 覆盖模块七的 noop</b>——这正是模块七"处罚令暂无真实消费方"
 * 缺口的补位。
 */
@Configuration
@ConditionalOnProperty(prefix = "tgg.federation", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(FederationProperties.class)
public class FederationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FederationConfiguration.class);

    private final FederationProperties properties;

    public FederationConfiguration(FederationProperties properties) {
        this.properties = properties;
    }

    /** 装配前先校验配置——避免"起了半个模块"才发现对端清单缺失。 */
    @PostConstruct
    void validateConfiguration() {
        int nodeCount = properties.parsedNodes().size();
        if (nodeCount == 0) {
            throw new TggConfigException(
                    "启用 tgg.federation.enabled 时必须配置 TGG_FEDERATION_NODES（对端节点清单 url|公钥Base64）"
                            + "——否则联邦开了却无从广播");
        }
        log.info("模块八 · 联邦治理已启用：对端节点 {} 个。", nodeCount);
    }

    /** 联邦管理员判定（全局白名单，热读取）。 */
    @Bean
    public FederationAdminGuard federationAdminGuard(
            com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService runtimeConfig) {
        FederationAdminGuard guard = new FederationAdminGuard(runtimeConfig);
        if (guard.size() == 0) {
            log.warn("未配置 tgg.federation.admins（TGG_FEDERATION_ADMINS）：联邦管理命令"
                    + "（/pending /approve /reject）将对任何人不可用。");
        }
        return guard;
    }

    /**
     * {@code /menu} 的「复核合规」可见性接缝（联邦部分）：裁决类命令走全局白名单，
     * 注册表判不出可见性，故把判定交给菜单侧——详见 {@link FederationMenuVisibility}。
     *
     * <p>随本类的 {@code tgg.federation.enabled} 门控一同出现/消失：模块未启用时连命令都不存在。
     */
    @Bean
    public MenuVisibility federationMenuVisibility(FederationAdminGuard federationAdminGuard) {
        return new FederationMenuVisibility(federationAdminGuard);
    }

    /** 本地执行：把已接受的处罚令落到各群封禁。 */
    @Bean
    public FederationBanService federationBanService(GroupConfigRepository groupConfigRepository,
                                                     ModerationActionSender actionSender) {
        return new FederationBanService(groupConfigRepository, actionSender);
    }

    /** 入站处理：验签 / 白名单 / 去重 / 落库。 */
    @Bean
    public FederationPenaltyService federationPenaltyService(FederationPenaltyRepository repository,
                                                             FederationBanService federationBanService) {
        return new FederationPenaltyService(properties.parsedNodes(), repository, federationBanService);
    }

    /**
     * 出站广播器：{@code @Primary} 覆盖模块七的 {@code PenaltyOrderPublisher.noop()}。
     *
     * <p>用 OkHttp 直发 JSON；联邦的信任来自 Ed25519 签名（写在 body 里），不使用 Bearer 鉴权。
     */
    @Bean
    @Primary
    public PenaltyOrderPublisher federationBroadcaster(ObjectMapper objectMapper) {
        return new FederationBroadcaster(properties.parsedNodes(), new OkHttpClient(), objectMapper);
    }

    /** 申诉服务。 */
    @Bean
    public FederationAppealService federationAppealService(FederationAppealRepository repository) {
        return new FederationAppealService(repository);
    }
}

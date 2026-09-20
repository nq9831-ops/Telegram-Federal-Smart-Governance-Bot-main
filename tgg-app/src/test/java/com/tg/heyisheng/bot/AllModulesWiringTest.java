package com.tg.heyisheng.bot;

import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import com.tg.heyisheng.bot.core.moderation.SensitiveTopicGuard;
import com.tg.heyisheng.bot.credit.CreditService;
import com.tg.heyisheng.bot.credit.PenaltyOrderPublisher;
import com.tg.heyisheng.bot.federation.FederationBroadcaster;
import com.tg.heyisheng.bot.federation.FederationPenaltyService;
import com.tg.heyisheng.bot.listing.ListingGroupService;
import com.tg.heyisheng.bot.listing.merchant.MerchantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨模块「<b>全开</b>」装配回归测试。
 *
 * <p><b>为什么必须有它</b>：既有装配测试（{@code AdmissionWiringTest} / {@code CreditWiringTest} /
 * {@code FederationWiringTest} / {@code ListingWiringTest} / {@code MerchantWiringTest}）都<b>只开自己那一个开关</b>；
 * 于是「多个模块同时开」这一组合从没被任何测试跑过。而本项目真实出现过的 P0，恰恰是
 * <b>组合</b>才会暴露的：{@code AdmissionConfiguration} 曾自造第二个 {@link ModerationActionSender}
 * bean，单开准入时没人察觉——是 2026-09-19 的<b>「全开实跑」</b>才发现「准入一开启，应用就起不来」。
 * 那次是一次性的人工验证，没有留下回归护栏；本测试把它固化成永久护栏。
 *
 * <p><b>为什么用 {@link DynamicPropertySource} 而不是注解常量</b>：模块七需要合法 Ed25519 私钥、
 * 模块八需要合法节点公钥，而 {@code @SpringBootTest(properties = …)} 只接受编译期常量。
 * 故在此<b>运行期生成</b>密钥并经 {@link DynamicPropertySource} 注入——私钥不落源码，
 * 也就不会污染密钥扫描（本仓库对「硬编码私钥」是明令禁止的）。
 *
 * <p><b>刻意不打开的开关</b>：{@code tgg.failover} / {@code tgg.command-menu} / {@code tgg.ai.deepseek}
 * ——它们会在启动或 {@code ApplicationReadyEvent} 时真的发网络请求（长轮询注册 / setMyCommands /
 * 打第三方），本测试只关心<b>装配层的 bean 冲突</b>，不引入网络抖动。
 */
@SpringBootTest
class AllModulesWiringTest {

    private static final String CREDIT_PRIVATE_KEY = generatePrivateKeyBase64();
    private static final String FEDERATION_NODE_SPEC =
            "https://peer.example.com|" + generatePublicKeyBase64();

    @DynamicPropertySource
    static void enableEveryModule(DynamicPropertyRegistry registry) {
        registry.add("tgg.admission.enabled", () -> "true");
        registry.add("tgg.credit.enabled", () -> "true");
        registry.add("tgg.credit.private-key", () -> CREDIT_PRIVATE_KEY);
        registry.add("tgg.federation.enabled", () -> "true");
        registry.add("tgg.federation.nodes", () -> FEDERATION_NODE_SPEC);
        registry.add("tgg.federation.admins", () -> "42");
        registry.add("tgg.listing.enabled", () -> "true");
        registry.add("tgg.merchant.enabled", () -> "true");
        registry.add("tgg.moderation.sensitive-grading-enabled", () -> "true");
    }

    @Autowired
    private ApplicationContext context;

    /** 全开时上下文必须能起来——这就是那次 P0「准入一开启，应用就起不来」的回归点。 */
    @Test
    void contextLoadsWithEveryModuleEnabled() {
        assertThat(context).isNotNull();
    }

    /**
     * 硬约束：{@code ModerationActionSender} 全容器唯一。
     *
     * <p>2026-09-19 的 P0 正是 {@code AdmissionConfiguration} 自造了第二个同类型 bean；两个候选会让
     * 所有按类型注入的消费方启动失败。单开准入的测试能发现它，本测试保证「多模块同时开」也不会再引入。
     */
    @Test
    void exactlyOneModerationActionSenderAcrossModules() {
        assertThat(context.getBeansOfType(ModerationActionSender.class)).hasSize(1);
    }

    /** 每个被打开的模块都要真的贡献出它的标志性 bean——避免「开关开着但装配没生效」的假绿。 */
    @Test
    void everyEnabledModuleContributesItsSignatureBean() {
        assertThat(context.getBean(CreditService.class)).as("模块七").isNotNull();
        assertThat(context.getBean(FederationPenaltyService.class)).as("模块八").isNotNull();
        assertThat(context.getBean(ListingGroupService.class)).as("模块五").isNotNull();
        assertThat(context.getBean(MerchantService.class)).as("模块六").isNotNull();
        assertThat(context.getBean(SensitiveTopicGuard.class)).as("模块九 §10.5").isNotNull();
    }

    /**
     * 跨模块接缝：模块七（处罚令）与模块八（广播）以 {@code @Primary} 约定优先级——
     * 同时开启时，按类型注入 {@link PenaltyOrderPublisher} 必须解析到联邦广播器
     * （否则处罚令只进本地账本、不出网，跨节点治理静默失效）。
     */
    @Test
    void federationBroadcasterWinsPenaltyOrderPublisherSeam() {
        assertThat(context.getBean(PenaltyOrderPublisher.class))
                .as("模块八的广播器须以 @Primary 覆盖模块七的 noop 出口")
                .isInstanceOf(FederationBroadcaster.class);
    }

    private static String generatePrivateKeyBase64() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static String generatePublicKeyBase64() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}

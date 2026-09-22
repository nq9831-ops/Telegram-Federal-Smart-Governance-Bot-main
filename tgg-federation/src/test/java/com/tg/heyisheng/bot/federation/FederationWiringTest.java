package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.core.platform.FederationAdminGuard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.common.exception.TggConfigException;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigRepository;
import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import com.tg.heyisheng.bot.credit.PenaltyOrderPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 模块八装配测试：开关语义 + 节点清单空即 fail-fast + 出站广播器覆盖 noop。
 */
class FederationWiringTest {

    private static final String NODE_SPEC = "https://peer.example.com|" + publicKeyBase64();

    private static String publicKeyBase64() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FederationConfiguration.class, RuntimeConfigTestStub.class)
            .withBean(GroupConfigRepository.class, () -> mock(GroupConfigRepository.class))
            // ApplicationContextRunner 不加载 JPA，仓库需手工补（同 RbacWiringTest 范式）
            .withBean(FederationPenaltyRepository.class, () -> mock(FederationPenaltyRepository.class))
            .withBean(FederationAppealRepository.class, () -> mock(FederationAppealRepository.class))
            .withBean(ModerationActionSender.class, ModerationActionSender::noop)
            // 判定器已上移 core（TggCoreConfiguration 恒在装配）；这里补替身，
            // hasSingleBean 断言同时守住「FederationConfiguration 不得重复注册」
            .withBean(FederationAdminGuard.class, () -> new FederationAdminGuard(java.util.List.of(42L)))
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());

    @Test
    void disabledByDefaultProducesNoFederationBeans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(FederationPenaltyService.class);
            assertThat(context).doesNotHaveBean(FederationBroadcaster.class);
            assertThat(context).doesNotHaveBean(FederationPenaltyController.class);
        });
    }

    @Test
    void enabledWithoutNodesFailsFast() {
        runner.withPropertyValues("tgg.federation.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .as("启用联邦却无对端节点，必须启动失败而非静默不广播")
                    .hasRootCauseInstanceOf(TggConfigException.class);
        });
    }

    @Test
    void enabledWithNodesWiresBroadcasterAsPrimaryPublisher() {
        runner.withPropertyValues(
                        "tgg.federation.enabled=true",
                        "tgg.federation.nodes=" + NODE_SPEC,
                        "tgg.federation.admins=42")
                .run(context -> {
                    assertThat(context).hasSingleBean(FederationPenaltyService.class);
                    assertThat(context).hasSingleBean(FederationAdminGuard.class);
                    // 广播器以 @Primary 形式提供为 PenaltyOrderPublisher（补模块七的 noop 缺口）
                    assertThat(context).hasSingleBean(PenaltyOrderPublisher.class);
                    assertThat(context.getBean(PenaltyOrderPublisher.class))
                            .isInstanceOf(FederationBroadcaster.class);
                });
    }

    @Test
    void malformedNodeSpecFailsFast() {
        runner.withPropertyValues("tgg.federation.enabled=true", "tgg.federation.nodes=not-a-valid-spec")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(TggConfigException.class);
                });
    }
}

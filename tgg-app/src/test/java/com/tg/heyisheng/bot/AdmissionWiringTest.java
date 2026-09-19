package com.tg.heyisheng.bot;

import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：{@code tgg.admission.enabled=true} 时上下文必须能装配起来。
 *
 * <p><b>背景（P0 · 2026-09-19 全开实跑发现）</b>：{@code AdmissionConfiguration} 曾自造第二个
 * {@link ModerationActionSender} bean，与 {@code TggCoreConfiguration} 里<b>无条件装配</b>的同类型
 * bean 撞车——任何按类型注入该接口的消费方（如 {@code ModerationReviewDecisionService}）都会报
 * {@code required a single bean, but 2 were found}，于是<b>准入一开启，应用就起不来</b>。
 *
 * <p>既有测试从不开该开关（测试 yml 与各 IT 都留默认 false），所以这条路径长期只是「看起来绿」。
 * 本测试把「准入启用」这一装配组合钉住，防止再次引入重复 sender。
 */
@SpringBootTest(properties = "tgg.admission.enabled=true")
class AdmissionWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithAdmissionEnabled() {
        assertThat(context).isNotNull();
    }

    /**
     * 不变量：{@code ModerationActionSender} 在容器里<b>有且只有一个</b>。
     *
     * <p>两个候选就会让所有按类型注入的消费方启动失败——单实例是本装配的硬约束，不是优化。
     */
    @Test
    void exactlyOneModerationActionSenderBean() {
        assertThat(context.getBeansOfType(ModerationActionSender.class)).hasSize(1);
    }
}

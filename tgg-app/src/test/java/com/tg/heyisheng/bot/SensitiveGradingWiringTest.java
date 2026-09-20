package com.tg.heyisheng.bot;

import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import com.tg.heyisheng.bot.core.moderation.SensitiveTopicGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：{@code tgg.moderation.sensitive-grading-enabled=true} 时上下文必须能装配起来。
 *
 * <p><b>为什么必须有它</b>：{@code SensitiveTopicGuard} 是 {@code TggCoreConfiguration} 里一个
 * <b>方法级</b> {@code @ConditionalOnProperty} bean，由 {@code UpdateDispatcher} 经
 * {@code ObjectProvider<SensitiveTopicGuard>} 消费（{@code getIfAvailable()} 在多候选时会抛
 * {@code NoUniqueBeanDefinitionException}）。测试 yml 与所有既有 IT 都留默认 {@code false}，
 * 于是「敏感分级启用」这条装配组合<b>在任何测试里都没被跑过</b>——正是本项目反复吃亏的
 * 「开关默认关闭 → 该装配路径长期假绿」形态。
 *
 * <p>本测试把这一组合钉住：上下文能起、guard 有且仅有一个、共享的
 * {@link ModerationActionSender} 仍唯一（避免再出现模块四那种「自造第二个同类型 bean」的 P0）。
 */
@SpringBootTest(properties = "tgg.moderation.sensitive-grading-enabled=true")
class SensitiveGradingWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithSensitiveGradingEnabled() {
        assertThat(context).isNotNull();
    }

    /** 开关打开后 guard 真的装配出来——否则「开了敏感分级」其实等于没开。 */
    @Test
    void sensitiveTopicGuardIsAssembledExactlyOnce() {
        assertThat(context.getBeansOfType(SensitiveTopicGuard.class)).hasSize(1);
    }

    /**
     * 不变量：{@code ModerationActionSender} 全容器唯一。
     *
     * <p>两个候选会让所有按类型注入的消费方（含 {@code ModerationReviewDecisionService}）
     * 启动失败——单实例是这条装配的硬约束，不是优化。
     */
    @Test
    void exactlyOneModerationActionSenderBean() {
        assertThat(context.getBeansOfType(ModerationActionSender.class)).hasSize(1);
    }
}

package com.tg.heyisheng.bot;

import com.tg.heyisheng.bot.core.dispatch.UpdateDispatcher;
import com.tg.heyisheng.bot.core.moderation.ModerationLayer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审核接线的<b>装配测试</b>——防止有人把审核层从组合根里摘掉而无人察觉。
 *
 * <p>回归背景：{@code UpdateDispatcher} 有一批不注入审核层的便利构造器
 * （两参 / 三参）。若组合根被改回用它们，编译照过、既有单测照绿，
 * 但生产会<b>静默退回「永不审核」</b>。
 *
 * <p>因此本测试不接受"不抛异常"这类弱断言——那同样发现不了该缺陷
 * （本类初版就是这么写的，后改为直读注入字段）。它直接从容器取出 bean
 * 并检视其内部依赖，确保接线真实存在。
 */
@SpringBootTest
class ModerationWiringTest {

    @Autowired
    private UpdateDispatcher updateDispatcherFromContainer;

    /**
     * 断言容器装配出的 dispatcher <b>确实持有</b>审核层。
     *
     * <p>用反射读私有字段是刻意的：这是装配事实，没有对外可观测路径——
     * 而正是"没有可观测路径"让这类静默失配容易溜过测试。
     */
    @Test
    void containerWiredDispatcherHoldsModerationLayer() throws Exception {
        Field field = UpdateDispatcher.class.getDeclaredField("moderationLayer");
        field.setAccessible(true);

        Object injected = field.get(updateDispatcherFromContainer);

        assertThat(injected)
                .as("组合根必须为 UpdateDispatcher 注入审核层；"
                        + "若退回两参构造器，生产将永不审核且无任何报错")
                .isNotNull()
                .isInstanceOf(ModerationLayer.class);
    }

    /** 容器里也应存在审核层 bean 本身（供装配注入）。 */
    @Test
    void containerProvidesModerationLayerBean(@Autowired ModerationLayer layer) {
        assertThat(layer).isNotNull();
        assertThat(layer.name()).isEqualTo("L1-regex");
    }
}

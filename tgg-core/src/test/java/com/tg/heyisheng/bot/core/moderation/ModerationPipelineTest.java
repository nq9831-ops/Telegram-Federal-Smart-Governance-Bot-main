package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.core.moderation.ModerationLayer.LayerHit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四层审核流水线测试。
 *
 * <p>核心契约（项目已验证的模式）：**按序询问、任一层命中即短路**——
 * 短路既是省算力，也是省外部调用（L3 会真的花钱、也真的把内容送出去）。
 * 因此"命中后不再问后续层"必须被断言，否则短路只是注释里的愿望。
 */
class ModerationPipelineTest {

    /** 记录自己被问过几次的替身层。 */
    private static final class RecordingLayer implements ModerationLayer {
        private final String name;
        private final LayerHit hit;
        private int calls;

        RecordingLayer(String name, LayerHit hit) {
            this.name = name;
            this.hit = hit;
        }

        @Override
        public Optional<LayerHit> inspect(String text) {
            calls++;
            return Optional.ofNullable(hit);
        }

        @Override
        public String name() {
            return name;
        }
    }

    private static LayerHit hit(String ruleId) {
        return new LayerHit(ruleId, RiskLevel.MEDIUM, false);
    }

    @Test
    void asksLayersInOrderAndStopsAtFirstHit() {
        RecordingLayer l1 = new RecordingLayer("L1", hit("R1"));
        RecordingLayer l3 = new RecordingLayer("L3", hit("R3"));
        ModerationPipeline pipeline = new ModerationPipeline(List.of(l1, l3));

        Optional<LayerHit> result = pipeline.inspect("内容");

        assertThat(result).isPresent();
        assertThat(result.get().ruleId()).isEqualTo("R1");
        assertThat(l3.calls).as("第一层命中后不得再问后续层（省算力与外部调用）").isZero();
    }

    @Test
    void continuesWhenEarlierLayerMisses() {
        RecordingLayer l1 = new RecordingLayer("L1", null);
        RecordingLayer l3 = new RecordingLayer("L3", hit("R3"));
        ModerationPipeline pipeline = new ModerationPipeline(List.of(l1, l3));

        assertThat(pipeline.inspect("内容")).isPresent()
                .get().extracting(LayerHit::ruleId).isEqualTo("R3");
        assertThat(l1.calls).as("未命中的层应被问过").isEqualTo(1);
    }

    @Test
    void returnsEmptyWhenNoLayerHits() {
        ModerationPipeline pipeline = new ModerationPipeline(List.of(
                new RecordingLayer("L1", null), new RecordingLayer("L3", null)));

        assertThat(pipeline.inspect("干净内容")).isEmpty();
    }

    /** 没有任何层时（例如全部默认关闭）不得抛异常。 */
    @Test
    void toleratesEmptyAndNullLayerList() {
        assertThat(new ModerationPipeline(List.of()).inspect("x")).isEmpty();
        assertThat(new ModerationPipeline(null).inspect("x")).isEmpty();
    }

    /** 单层抛异常不得让整条流水线崩掉——审核是增强，不该打断消息处理。 */
    @Test
    void toleratesLayerFailureAndKeepsAskingLaterLayers() {
        ModerationLayer failing = new ModerationLayer() {
            @Override
            public Optional<LayerHit> inspect(String text) {
                throw new IllegalStateException("模型服务不可用");
            }

            @Override
            public String name() {
                return "L3-broken";
            }
        };
        RecordingLayer l4 = new RecordingLayer("L4", hit("R4"));

        Optional<LayerHit> result = new ModerationPipeline(List.of(failing, l4)).inspect("内容");

        assertThat(result).as("某层故障不应吞掉后续层的判定").isPresent();
        assertThat(result.get().ruleId()).isEqualTo("R4");
    }

    @Test
    void exposesLayerNamesForDiagnostics() {
        ModerationPipeline pipeline = new ModerationPipeline(List.of(
                new RecordingLayer("L1-regex", null), new RecordingLayer("L3-deepseek", null)));

        assertThat(pipeline.layerNames()).containsExactly("L1-regex", "L3-deepseek");
        assertThat(new ArrayList<>(pipeline.layerNames())).hasSize(2);
    }
}

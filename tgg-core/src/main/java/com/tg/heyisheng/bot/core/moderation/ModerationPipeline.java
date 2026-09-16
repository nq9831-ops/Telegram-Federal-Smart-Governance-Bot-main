package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.core.moderation.ModerationLayer.LayerHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 四层审核流水线：按序询问各层，**任一层命中即短路**。
 *
 * <p><b>为什么需要聚合器</b>：{@link ModerationLayer} 是**单层**契约（L1 正则 / L2 ML / L3 DeepSeek / L4 零样本
 * 各自实现它）。调用方要的是"这些层合起来怎么判"，那是另一个职责——把顺序、短路与容错集中在这里，
 * 各层才能保持独立可替换。
 *
 * <p><b>短路是硬要求</b>：L3 会真的发起外部调用（花钱、且把正文送出站），
 * 前层已判定违规时再问下去纯属浪费与风险。
 *
 * <p><b>某层故障不得中断整条流水线</b>：审核是**增强**而非唯一防线，
 * 某一层（如模型服务）不可用时跳过它、继续问后续层；失败记日志。
 * 注意这等于"该层在故障期间等于没审"——必须让日志与部署文档说清楚，别被误当作已覆盖。
 */
public class ModerationPipeline {

    private static final Logger log = LoggerFactory.getLogger(ModerationPipeline.class);

    private final List<ModerationLayer> layers;

    public ModerationPipeline(List<ModerationLayer> layers) {
        this.layers = layers == null ? List.of() : List.copyOf(layers);
    }

    /**
     * 按序询问各层，返回**第一个**命中结果。
     *
     * @param text 待检测正文；null/空时各层自行决定（L1 会直接放行）
     * @return 第一个命中；全部未命中或没有可用层时为空
     */
    public Optional<LayerHit> inspect(String text) {
        for (ModerationLayer layer : layers) {
            try {
                Optional<LayerHit> hit = layer.inspect(text);
                if (hit.isPresent()) {
                    return hit;
                }
            } catch (RuntimeException ex) {
                // 单层故障跳过，继续问后续层——审核是增强，不该因某个模型服务抖动而整体停摆
                log.warn("审核层 {} 执行失败，已跳过该层并继续", layer.name(), ex);
            }
        }
        return Optional.empty();
    }

    /** 已装载的层名（按询问顺序），供诊断与装配测试使用。 */
    public List<String> layerNames() {
        return layers.stream().map(ModerationLayer::name).toList();
    }

    /** 已装载的层数。 */
    public int size() {
        return layers.size();
    }
}

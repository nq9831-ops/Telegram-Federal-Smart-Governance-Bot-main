package com.tg.heyisheng.bot.core.moderation;

import java.util.Optional;

/**
 * 审核层。
 *
 * <p>V5.0 的四层检测架构共用本契约：L1 正则 → L2 ML → L3 DeepSeek → L4 零样本。
 * 流水线按顺序询问各层，<b>任一层命中即可短路</b>（节省后续层的算力与外部调用）。
 *
 * <p><b>层的输入是消息正文</b>，因此调用方必须确保正文在内存中即时消费、
 * 不落盘、不进日志（见 {@code MessageScrubber}）。
 */
public interface ModerationLayer {

    /**
     * 对本层而言，该内容是否命中。
     *
     * @param text 待检测的文本；null 或空表示无内容可查
     * @return 命中结果；未命中返回空
     */
    Optional<LayerHit> inspect(String text);

    /** 层标识，用于日志与统计（如 {@code "L1-regex"}）。 */
    String name();

    /**
     * 单层命中结果：只描述「哪条规则命中、风险多高」，<b>不回显命中内容</b>。
     *
     * @param ruleId    命中的规则标识
     * @param riskLevel 风险等级
     * @param hardLine  是否硬性红线
     */
    record LayerHit(String ruleId, RiskLevel riskLevel, boolean hardLine) {
    }
}

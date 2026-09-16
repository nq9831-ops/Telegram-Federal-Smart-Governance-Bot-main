package com.tg.heyisheng.bot.core.moderation;

/**
 * 风险等级。
 *
 * <p>对应 V5.0 的分级处置：
 * <ul>
 *   <li>{@link #NONE} 放行</li>
 *   <li>{@link #LOW} / {@link #MEDIUM} 交人工复核（V5.0：中风险 80% 人工复核）</li>
 *   <li>{@link #HIGH} 高风险，同样需人工复核，但优先级更高</li>
 * </ul>
 *
 * <p><b>硬性红线不走本枚举</b>：它由 {@code ModerationRule.hardLine} 单独标记，
 * 因为红线的处置是「立即冻结、不等复核」，与等级是两回事。
 */
public enum RiskLevel {

    /** 未命中任何规则。 */
    NONE(0),

    /** 轻度疑似违规。 */
    LOW(1),

    /** 中度疑似违规。 */
    MEDIUM(2),

    /** 高度疑似违规。 */
    HIGH(3);

    private final int severity;

    RiskLevel(int severity) {
        this.severity = severity;
    }

    /** 数值越大越严重，用于从多条命中里取最高等级。 */
    public int severity() {
        return severity;
    }

    public boolean isAtLeast(RiskLevel other) {
        return this.severity >= other.severity;
    }
}

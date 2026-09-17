package com.tg.heyisheng.bot.core.moderation;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 一条审核规则（L1 正则层）。
 *
 * <p>规则只描述「什么样的内容算违规」，不含处置动作——处置由上层按
 * {@link RiskLevel} 决定（参见 V5.0 的分级处理：轻度删除+警告，重度删除+禁言）。
 *
 * @param id         规则标识，用于审计与命中统计（必须稳定，不要用随机值）
 * @param name       人类可读名称，仅用于日志与后台展示
 * @param pattern    匹配模式
 * @param riskLevel  命中后的风险等级
 * @param hardLine   是否属于「硬性红线」（诈骗/儿童色情等）——
 *                   硬红线需立即冻结且不可豁免，V5.0 明确要求
 */
public record ModerationRule(String id,
                             String name,
                             Pattern pattern,
                             RiskLevel riskLevel,
                             boolean hardLine) {

    public ModerationRule {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("规则 id 不可为空（审计与统计依赖它）");
        }
        if (pattern == null) {
            throw new IllegalArgumentException("规则 pattern 不可为空");
        }
        if (riskLevel == null) {
            throw new IllegalArgumentException("规则 riskLevel 不可为空");
        }
    }

    /** 便捷构造：默认非硬红线。 */
    public static ModerationRule of(String id, String name, String regex, RiskLevel riskLevel) {
        return new ModerationRule(id, name, Pattern.compile(regex), riskLevel, false);
    }

    /**
     * 便捷构造：显式指定硬红线标记。
     *
     * <p><b>为什么单独开一个工厂</b>：动态来源的规则（按群教学规则等）的硬红线标记存在数据里，
     * 不是代码里写死的常量。少了这个工厂，调用方只能自己去调 5 参构造器，
     * 而「顺手用 4 参的 {@link #of} 然后忘掉 hardLine」正是本项目刚踩过的坑
     * （{@code TaughtRule.toRule()} 曾把库里的 hard_line 静默丢弃，使该列写完即无人消费）。
     */
    public static ModerationRule of(String id, String name, String regex, RiskLevel riskLevel,
                                    boolean hardLine) {
        return new ModerationRule(id, name, Pattern.compile(regex), riskLevel, hardLine);
    }

    /** 便捷构造：硬红线。 */
    public static ModerationRule hardLine(String id, String name, String regex) {
        return new ModerationRule(id, name, Pattern.compile(regex), RiskLevel.HIGH, true);
    }

    /** 该内容是否命中本规则。 */
    public boolean matches(String text) {
        return text != null && pattern.matcher(text).find();
    }

    /** 便于测试与配置展示：全部规则。 */
    public static List<ModerationRule> none() {
        return List.of();
    }
}

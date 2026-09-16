package com.tg.heyisheng.bot.core.moderation;

import java.util.List;
import java.util.Optional;

/**
 * 审核判定结果。
 *
 * <p><b>刻意不含消息正文</b>：本对象会被挂到 {@code UpdateContext}、进而进入审计与日志，
 * 因此只能携带「判定结论」，不能携带被判定内容。命中片段也只记录<b>规则 id</b> 与
 * 匹配位置，不回显原文——这既满足 V5.0 的「违规片段脱敏存储」，
 * 也避免正文经审核结果这条侧路泄露。
 *
 * @param riskLevel   综合风险等级（多条命中取最高）
 * @param hardLine    是否命中硬性红线（诈骗/儿童色情等，不可豁免）
 * @param matchedRuleIds 命中的规则 id 列表，供审计与规则效果统计
 */
public record ModerationVerdict(RiskLevel riskLevel,
                                boolean hardLine,
                                List<String> matchedRuleIds) {

    public ModerationVerdict {
        if (riskLevel == null) {
            throw new IllegalArgumentException("riskLevel 不可为空");
        }
        matchedRuleIds = matchedRuleIds == null ? List.of() : List.copyOf(matchedRuleIds);
    }

    /** 未命中任何规则的放行结果。 */
    public static ModerationVerdict clean() {
        return new ModerationVerdict(RiskLevel.NONE, false, List.of());
    }

    /** 是否需要人工复核。 */
    public boolean needsReview() {
        return riskLevel != RiskLevel.NONE;
    }

    /** 是否应当直接拦截（红线不走复核）。 */
    public boolean shouldFreezeImmediately() {
        return hardLine;
    }

    /** 便于上游读取：命中的第一条规则（诊断用）。 */
    public Optional<String> firstMatchedRule() {
        return matchedRuleIds.stream().findFirst();
    }
}

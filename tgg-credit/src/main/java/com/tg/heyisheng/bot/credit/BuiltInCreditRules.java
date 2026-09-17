package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.core.credit.CreditEvent;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;

/**
 * 内置信用规则（模块七）：按硬红线标记与风险等级决定扣分。
 *
 * <p><b>为什么 hardLine 单独判、排在 severity 之前</b>：硬红线（诈骗/儿童色情）的处置是
 * "立即触底"，与等级是两回事。若先按 severity 比较，一条同时被标为 LOW 的硬红线会被扣得比
 * HIGH 还少——这正是本项目在 {@code RegexLayer} 上踩过的"低等级掩盖高风险"坑，故此处显式分离。
 *
 * <p>⚠️ <b>以下数值是设计文档给出的默认值，不是 V5.0 规格</b>（该章节不在仓库）：
 * 起始 100、硬红线 −100（触底）、HIGH −30、MEDIUM −15、LOW −5。须在真实群校准。
 */
public final class BuiltInCreditRules implements CreditRuleEngine {

    /** 硬红线：一扣到底（起始 100 → 0），不等等级比较。 */
    public static final int HARD_LINE_DELTA = -100;
    public static final int HIGH_DELTA = -30;
    public static final int MEDIUM_DELTA = -15;
    public static final int LOW_DELTA = -5;
    public static final int NONE_DELTA = 0;

    @Override
    public int deltaFor(CreditEvent event) {
        if (event == null) {
            return NONE_DELTA;
        }
        // 硬红线优先——无论其 severity 报的是哪一档
        if (event.hardLine()) {
            return HARD_LINE_DELTA;
        }
        return severityDelta(event.severity());
    }

    private static int severityDelta(RiskLevel severity) {
        if (severity == null) {
            return NONE_DELTA;
        }
        return switch (severity) {
            case HIGH -> HIGH_DELTA;
            case MEDIUM -> MEDIUM_DELTA;
            case LOW -> LOW_DELTA;
            case NONE -> NONE_DELTA;
        };
    }
}

package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.core.credit.CreditEvent;
import com.tg.heyisheng.bot.core.credit.CreditEventType;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;

import java.util.Map;

/**
 * 内置信用规则（模块七）：按硬红线标记与风险等级决定扣分。
 *
 * <p><b>为什么 hardLine 单独判、排在 severity 之前</b>：硬红线（诈骗/儿童色情）的处置是
 * "立即触底"，与等级是两回事。若先按 severity 比较，一条同时被标为 LOW 的硬红线会被扣得比
 * HIGH 还少——这正是本项目在 {@code RegexLayer} 上踩过的"低等级掩盖高风险"坑，故此处显式分离。
 *
 * <p><b>模块十二新增：按事件类型的固定增量表</b>（{@link #TYPE_DELTAS}）——担保交易"完成加分"
 * 需要正增量，而 severity 路径恒 {@code ≤ 0}，表达不了该语义。类型表<b>优先于</b> severity 判定；
 * 未登记的类型仍走原路径（既有 6 个类型行为逐字不变）。
 *
 * <p>⚠️ <b>以下数值是设计文档给出的默认值，不是 V5.0 规格</b>（该章节不在仓库）：
 * 起始 100、硬红线 −100（触底）、HIGH −30、MEDIUM −15、LOW −5、交易完成 +10。须在真实群校准。
 */
public final class BuiltInCreditRules implements CreditRuleEngine {

    /** 硬红线：一扣到底（起始 100 → 0），不等等级比较。 */
    public static final int HARD_LINE_DELTA = -100;
    public static final int HIGH_DELTA = -30;
    public static final int MEDIUM_DELTA = -15;
    public static final int LOW_DELTA = -5;
    public static final int NONE_DELTA = 0;

    /**
     * 模块十二：担保交易完成加分——<b>本项目唯一的正增量</b>（见 {@link #TYPE_DELTAS}）。
     * 设计默认值（+10，与 {@link #LOW_DELTA} 量级对称），待真实群校准。
     */
    public static final int ESCROW_COMPLETED_DELTA = 10;

    /** 模块十二：担保交易欺诈——一扣到底，不等等级比较（同硬红线纪律）。 */
    public static final int ESCROW_FRAUD_DELTA = -100;

    /**
     * 按事件类型固定的增量（<b>优先于</b> severity 判定）。
     *
     * <p><b>为什么需要这张表</b>：本类既有路径只从 severity 推增量，而 severity 的语义是"风险等级"
     * （四档、增量恒 {@code ≤ 0}）——<b>表达不了"完成交易加分"</b>。把交易完成硬报成某个 severity
     * 只会扣分或记 0，属静默失效（守门：{@code escrowCompletedAddsPoints}）。
     *
     * <p><b>只登记确需固定增量的类型</b>：未登记的类型（既有 6 个）仍走 severity 路径，
     * 行为逐字不变（守门：{@code nonEscrowTypesStillFollowSeverityPath}）。
     */
    private static final Map<CreditEventType, Integer> TYPE_DELTAS = Map.of(
            CreditEventType.ESCROW_COMPLETED, ESCROW_COMPLETED_DELTA,
            CreditEventType.ESCROW_FRAUD, ESCROW_FRAUD_DELTA);

    @Override
    public int deltaFor(CreditEvent event) {
        if (event == null) {
            return NONE_DELTA;
        }
        // 类型表优先：登记过固定增量的类型不看 severity（"完成加分"与"欺诈触底"都属此类）
        Integer byType = TYPE_DELTAS.get(event.eventType());
        if (byType != null) {
            return byType;
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

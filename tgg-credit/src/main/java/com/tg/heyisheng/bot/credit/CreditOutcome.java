package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.core.credit.CreditSubjectType;

/**
 * 一次记账的结果（模块七）。
 *
 * @param subjectType 被评分主体类型
 * @param subjectId   被评分主体 id
 * @param score       记账后的最新分值
 * @param penalty     由分值判定的处罚档位（{@link PenaltyType#NONE} 表示未触发）
 */
public record CreditOutcome(CreditSubjectType subjectType,
                            long subjectId,
                            int score,
                            PenaltyType penalty) {

    public CreditOutcome {
        if (penalty == null) {
            penalty = PenaltyType.NONE;
        }
    }

    /** 本次记账是否触发了处罚（非 NONE）。 */
    public boolean triggeredPenalty() {
        return penalty != PenaltyType.NONE;
    }
}

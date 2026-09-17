package com.tg.heyisheng.bot.credit;

/**
 * 信用分阈值表（模块七）：由分值判定应触发的处罚档位。
 *
 * <p>抽成纯静态函数是为了<b>可独立测试边界</b>——阈值判定最容易在 59/60、29/30 这类
 * 临界值上出错，而它不依赖数据库、不依赖时钟，是理想的单测对象。
 *
 * <p>⚠️ <b>以下阈值是设计文档给出的默认值，不是 V5.0 规格</b>（该章节不在仓库）：
 * &lt;60 警告、&lt;30 禁言、≤0 上报联邦。须在真实群校准。
 */
public final class CreditThresholds {

    /** 低于此分触发 WARN。 */
    public static final int WARN_BELOW = 60;
    /** 低于此分触发 MUTE。 */
    public static final int MUTE_BELOW = 30;
    /** 低于或等于此分触发 REPORT_TO_FEDERATION（含本地移出）。 */
    public static final int FEDERATION_AT_OR_BELOW = 0;

    private CreditThresholds() {
    }

    /**
     * 由当前分值判定处罚档位。
     *
     * <p>判定自严重到轻微，第一个命中即返回——顺序写反会让轻微档位掩盖严重档位。
     *
     * @param score 当前信用分
     * @return 对应处罚档位；未跨任何阈值为 {@link PenaltyType#NONE}
     */
    public static PenaltyType penaltyFor(int score) {
        if (score <= FEDERATION_AT_OR_BELOW) {
            return PenaltyType.REPORT_TO_FEDERATION;
        }
        if (score < MUTE_BELOW) {
            return PenaltyType.MUTE;
        }
        if (score < WARN_BELOW) {
            return PenaltyType.WARN;
        }
        return PenaltyType.NONE;
    }
}

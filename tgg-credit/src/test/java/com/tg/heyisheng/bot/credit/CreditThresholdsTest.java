package com.tg.heyisheng.bot.credit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 信用分阈值判定的单测——重点在**临界值**（59/60、29/30、0）。
 *
 * <p>档位判定最容易在边界写错（用 &lt; 还是 &lt;=），而它决定"是否处罚、处到哪一档"，
 * 写反会让该禁言的只警告、或该上报的只禁言。
 */
class CreditThresholdsTest {

    @Test
    void atWarnThresholdIsNotYetWarned() {
        // 60 恰好在阈值上——不算低于，故不处罚
        assertThat(CreditThresholds.penaltyFor(60)).isEqualTo(PenaltyType.NONE);
    }

    @Test
    void justBelowWarnThresholdIsWarned() {
        assertThat(CreditThresholds.penaltyFor(59)).isEqualTo(PenaltyType.WARN);
    }

    @Test
    void atMuteThresholdIsStillWarnedNotMuted() {
        // 30 未低于 MUTE_BELOW(30)，故仍是 WARN
        assertThat(CreditThresholds.penaltyFor(30)).isEqualTo(PenaltyType.WARN);
    }

    @Test
    void justBelowMuteThresholdIsMuted() {
        assertThat(CreditThresholds.penaltyFor(29)).isEqualTo(PenaltyType.MUTE);
    }

    @Test
    void justAboveZeroIsMutedNotFederated() {
        assertThat(CreditThresholds.penaltyFor(1)).isEqualTo(PenaltyType.MUTE);
    }

    @Test
    void atZeroIsReportedToFederation() {
        assertThat(CreditThresholds.penaltyFor(0)).isEqualTo(PenaltyType.REPORT_TO_FEDERATION);
    }

    @Test
    void initialScoreTriggersNothing() {
        assertThat(CreditThresholds.penaltyFor(CreditService.INITIAL_SCORE)).isEqualTo(PenaltyType.NONE);
    }
}

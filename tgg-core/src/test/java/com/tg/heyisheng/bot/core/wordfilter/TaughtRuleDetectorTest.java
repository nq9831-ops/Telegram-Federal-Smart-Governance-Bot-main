package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.core.moderation.ModerationRule;
import com.tg.heyisheng.bot.core.moderation.ModerationVerdict;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 教学规则检测器单测：守「多条命中取最严重」与「硬红线优先」两条纪律
 * （照 {@code UpdateDispatcher.worseOf} 的既有约定——低等级命中不得掩盖高等级）。
 */
class TaughtRuleDetectorTest {

    private static final long CHAT = -100900888L;

    private final TaughtRuleService service = mock(TaughtRuleService.class);
    private final TaughtRuleDetector detector = new TaughtRuleDetector(service);

    private void rules(ModerationRule... rules) {
        when(service.rulesFor(CHAT)).thenReturn(List.of(rules));
    }

    @Test
    void yieldsEmptyWhenChatIdOrTextMissing() {
        assertThat(detector.inspect(null, "hi")).isEmpty();
        assertThat(detector.inspect(CHAT, null)).isEmpty();
        assertThat(detector.inspect(CHAT, "")).isEmpty();
    }

    @Test
    void yieldsEmptyWhenNoRulesMatch() {
        rules(ModerationRule.of("R1", "广告", "casino", RiskLevel.HIGH));

        assertThat(detector.inspect(CHAT, "今天天气不错")).isEmpty();
    }

    @Test
    void worstRiskLevelWins() {
        rules(ModerationRule.of("LOW_RULE", "轻", "spam", RiskLevel.LOW),
                ModerationRule.of("MED_RULE", "中", "spam\\d+", RiskLevel.MEDIUM));

        Optional<ModerationVerdict> verdict = detector.inspect(CHAT, "spam123");

        assertThat(verdict).isPresent();
        assertThat(verdict.get().riskLevel())
                .as("两条都命中时取更严重的那档")
                .isEqualTo(RiskLevel.MEDIUM);
        assertThat(verdict.get().matchedRuleIds()).containsExactly("LOW_RULE", "MED_RULE");
    }

    @Test
    void hardLineFlagPropagatesEvenWhenAnotherRuleHitsHarderLookingText() {
        rules(ModerationRule.of("MILD", "轻", "word", RiskLevel.LOW),
                ModerationRule.hardLine("HARD", "红线", "word\\d+"));

        Optional<ModerationVerdict> verdict = detector.inspect(CHAT, "word7");

        assertThat(verdict).isPresent();
        assertThat(verdict.get().hardLine())
                .as("任一硬红线命中即整条判硬红线——其处置是不等复核")
                .isTrue();
    }

    @Test
    void nonHardLineRuleDoesNotSetHardLineFlag() {
        rules(ModerationRule.of("MILD", "轻", "word", RiskLevel.MEDIUM));

        assertThat(detector.inspect(CHAT, "word")).isPresent();
        assertThat(detector.inspect(CHAT, "word").orElseThrow().hardLine()).isFalse();
    }
}

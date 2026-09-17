package com.tg.heyisheng.bot.core.moderation;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 敏感话题分级检测器测试（模块九 §10.5）。
 *
 * <p><b>守两件事</b>：① 分级**不是红线**（{@code hardLine=false}，绝不触发封禁）；
 * ② 群标签豁免是**按话题**的，不是「有标签就全豁免」。
 */
class SensitiveTopicDetectorTest {

    private static final long CHAT = -100900700L;

    private final GroupTopicTagService tags = mock(GroupTopicTagService.class);
    private final SensitiveTopicDetector detector = new SensitiveTopicDetector(tags);

    private void groupTags(String... values) {
        when(tags.tagsOf(CHAT)).thenReturn(Set.of(values));
    }

    @Test
    void flagsSensitiveTopicAtItsLevel() {
        groupTags();

        ModerationVerdict verdict = detector.inspect(CHAT, "快来博彩下注，稳赚不赔").orElseThrow();

        assertThat(verdict.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(verdict.hardLine()).as("敏感话题是分级、不是红线——绝不触发封禁").isFalse();
        assertThat(verdict.matchedRuleIds()).containsExactly("SENSITIVE_GAMBLING");
    }

    @Test
    void groupTagExemptsThatTopic() {
        groupTags("gambling");

        assertThat(detector.inspect(CHAT, "博彩下注"))
                .as("本群已声明 gambling → 该话题豁免").isEmpty();
    }

    @Test
    void exemptionIsPerTopicNotGlobal() {
        groupTags("gambling");

        ModerationVerdict verdict = detector.inspect(CHAT, "荐股带单，内部消息").orElseThrow();

        assertThat(verdict.matchedRuleIds())
                .as("豁免只对声明的话题生效——声明 gambling 不该顺带豁免 finance")
                .containsExactly("SENSITIVE_FINANCE");
    }

    @Test
    void takesHighestLevelAcrossTopics() {
        groupTags();

        ModerationVerdict verdict = detector.inspect(CHAT, "博彩下注，另外聊聊政治选举").orElseThrow();

        assertThat(verdict.riskLevel()).as("取命中话题的最高等级").isEqualTo(RiskLevel.MEDIUM);
        assertThat(verdict.matchedRuleIds())
                .containsExactlyInAnyOrder("SENSITIVE_GAMBLING", "SENSITIVE_POLITICS");
    }

    @Test
    void ordinaryContentIsNotSensitive() {
        groupTags();

        assertThat(detector.inspect(CHAT, "今天天气不错")).isEmpty();
        assertThat(detector.inspect(CHAT, null)).isEmpty();
    }
}

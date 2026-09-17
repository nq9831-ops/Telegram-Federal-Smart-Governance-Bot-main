package com.tg.heyisheng.bot.core.moderation;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 敏感话题分级检测器测试（模块九 §10.5）。
 *
 * <p><b>话题集必须以 V5.0 原文为准</b>：分类定义 = 恐怖活动 / 宗教主义 / 国际政治 / 政治；
 * 不可豁免 = 恐怖活动 / 极端主义 / 煽动战争。此前天枢的推测稿把它写成了
 * gambling / adult / finance… 并让「标签一豁免就全免」——本类即这两条的护栏
 * （冲突清单见 `.rivet/HANDOFF.md` 关键产物段）。
 */
class SensitiveTopicDetectorTest {

    private static final long CHAT = -100900700L;

    private final GroupTopicTagService tags = mock(GroupTopicTagService.class);
    private final SensitiveTopicDetector detector = new SensitiveTopicDetector(tags);

    private void groupTags(String... values) {
        when(tags.tagsOf(CHAT)).thenReturn(Set.of(values));
    }

    private static String ruleIds(ModerationVerdict verdict) {
        return String.join("|", verdict.matchedRuleIds());
    }

    @Test
    void flagsTheFourDocumentedTopics() {
        groupTags();

        assertThat(ruleIds(detector.inspect(CHAT, "招募圣战分子，教做炸弹").orElseThrow()))
                .as("恐怖活动").contains("SENSITIVE_TERRORISM");
        assertThat(ruleIds(detector.inspect(CHAT, "宣传宗教极端主义").orElseThrow()))
                .as("宗教主义").contains("SENSITIVE_RELIGIONISM");
        assertThat(ruleIds(detector.inspect(CHAT, "聊聊国际政治与制裁").orElseThrow()))
                .as("国际政治").contains("SENSITIVE_INTL_POLITICS");
        assertThat(ruleIds(detector.inspect(CHAT, "组织游行抗议选举结果").orElseThrow()))
                .as("政治").contains("SENSITIVE_POLITICS");
    }

    @Test
    void sensitiveTopicsAreNeverHardLine() {
        groupTags();

        assertThat(detector.inspect(CHAT, "组织游行抗议").orElseThrow().hardLine())
                .as("敏感话题是分级、不是红线——绝不触发封禁").isFalse();
    }

    @Test
    void groupTagExemptsExemptableTopics() {
        groupTags("politics");

        assertThat(detector.inspect(CHAT, "组织游行抗议"))
                .as("本群声明 politics → 该话题豁免").isEmpty();
    }

    @Test
    void nonExemptableTopicsIgnoreGroupTags() {
        groupTags("terrorism", "religionism", "intl_politics", "politics");

        assertThat(detector.inspect(CHAT, "招募圣战分子，教做炸弹"))
                .as("恐怖活动 / 极端主义 / 煽动战争 —— **不可豁免**，声明标签也不行")
                .isPresent();
    }

    @Test
    void ordinaryContentIsNotSensitive() {
        groupTags();

        assertThat(detector.inspect(CHAT, "今天天气不错")).isEmpty();
        assertThat(detector.inspect(CHAT, null)).isEmpty();
    }
}

package com.tg.heyisheng.bot.core.moderation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 正则层测试。
 *
 * <p>除功能外，还锁住两条不变量：命中结果<b>不回显正文</b>、
 * 多条命中时<b>取最严重等级</b>。
 */
class RegexLayerTest {

    private static final ModerationRule SPAM_CASINO =
            ModerationRule.of("SPAM_CASINO", "赌场广告", "\\d+\\s*casino", RiskLevel.LOW);
    private static final ModerationRule SPAM_LUCKY =
            ModerationRule.of("SPAM_LUCKY", "幸运号码广告", "lucky\\s*\\d{4}", RiskLevel.MEDIUM);
    private static final ModerationRule HARD_FRAUD =
            ModerationRule.hardLine("HARD_FRAUD", "诈骗", "(?i)send\\s+private\\s+key");

    private final RegexLayer layer = new RegexLayer(List.of(SPAM_CASINO, SPAM_LUCKY, HARD_FRAUD));

    @Test
    void detectsMatchingRule() {
        assertThat(layer.inspect("加入 888casino 立即发财"))
                .isPresent()
                .get()
                .extracting(ModerationLayer.LayerHit::ruleId)
                .isEqualTo("SPAM_CASINO");
    }

    @Test
    void cleanTextYieldsEmpty() {
        assertThat(layer.inspect("今天天气不错，一起吃饭吗")).isEmpty();
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertThat(layer.inspect(null)).isEmpty();
        assertThat(layer.inspect("")).isEmpty();
    }

    /** 多条命中时取最严重的那条——否则低等级规则会掩盖高等级风险。 */
    @Test
    void picksTheMostSevereAmongMultipleMatches() {
        var hit = layer.inspect("888casino 和 lucky 1234 都看看");

        assertThat(hit).isPresent();
        assertThat(hit.get().ruleId()).isEqualTo("SPAM_LUCKY");
        assertThat(hit.get().riskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }

    /** 硬红线优先于一切：它的处置是立即冻结、不等复核。 */
    @Test
    void hardLineWinsImmediately() {
        var hit = layer.inspect("888casino 广告，另外 SEND PRIVATE KEY 给我");

        assertThat(hit).isPresent();
        assertThat(hit.get().hardLine()).as("硬红线必须被标记").isTrue();
        assertThat(hit.get().ruleId()).isEqualTo("HARD_FRAUD");
    }

    @Test
    void exposesLayerNameAndRuleCount() {
        assertThat(layer.name()).isEqualTo("L1-regex");
        assertThat(layer.ruleCount()).isEqualTo(3);
    }

    @Test
    void emptyRuleSetMatchesNothing() {
        assertThat(new RegexLayer(List.of()).inspect("888casino")).isEmpty();
    }

    /**
     * 隐私不变量：命中结果的结构里不得存在能承载正文的字段。
     *
     * <p>该结果会被挂到上下文、进入审计与日志——若它持有正文，
     * 消息原文就会经审核结果这条侧路泄露，绕过 {@code MessageScrubber}。
     */
    @Test
    void hitStructureCannotCarryMessageText() {
        List<String> componentNames = Arrays.stream(ModerationLayer.LayerHit.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames)
                .doesNotContain("text", "content", "message", "snippet", "matchedText", "raw");
    }

    /** 规则自身必须是良构的——id 为空会让审计与统计失真。 */
    @Test
    void rejectsRuleWithoutId() {
        assertThatThrownBy(() -> ModerationRule.of("", "无名规则", "x", RiskLevel.LOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void verdictAggregatesSafely() {
        assertThat(ModerationVerdict.clean().needsReview()).isFalse();
        assertThat(ModerationVerdict.clean().riskLevel()).isEqualTo(RiskLevel.NONE);
        assertThat(new ModerationVerdict(RiskLevel.HIGH, true, List.of("HARD_FRAUD"))
                .shouldFreezeImmediately()).isTrue();
    }
}

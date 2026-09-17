package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.core.moderation.ModerationRule;
import com.tg.heyisheng.bot.core.moderation.ModerationVerdict;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 教学规则服务单测（模块九 §10.3）。
 *
 * <p><b>本类守两类东西</b>：
 * <ol>
 *   <li><b>三道正则闸门</b>——管理员写的正则会被热路径吃下，一条 {@code (a+)+} 就足以让机器人
 *       卡死在单条消息上（DoS）。因此「能编译 / 长度受限 / 非嵌套量词」三条必须各自有反例；</li>
 *   <li><b>缓存语义</b>——{@code rulesFor} 在每条消息上调用，绝不能每消息查库
 *       （项目已有同类既有缺陷，见 KNOWN-ISSUES P2#1）；且写操作必须让缓存<b>立即</b>失效，
 *       否则「教完要等 TTL 才生效」。</li>
 * </ol>
 */
class TaughtRuleServiceTest {

    private static final long CHAT = -100900888L;
    private static final Instant NOW = Instant.parse("2026-09-17T03:00:00Z");

    private final TaughtRuleRepository repository = mock(TaughtRuleRepository.class);
    private final TaughtRuleService service =
            new TaughtRuleService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    private static TaughtRule saved(String ruleId, String regex, RiskLevel level, boolean hardLine) {
        return new TaughtRule(CHAT, ruleId, "描述", regex, level, hardLine, 42L, NOW);
    }

    // ---------- 三道正则闸门 ----------

    @Test
    void rejectsRegexThatDoesNotCompile() {
        assertThatThrownBy(() -> service.teach(CHAT, "R1", "n", "([", RiskLevel.MEDIUM, false, 42L))
                .isInstanceOf(TggException.class)
                .hasMessageContaining("无法编译");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsOverlongRegex() {
        String tooLong = "a".repeat(TaughtRuleService.MAX_REGEX_LENGTH + 1);

        assertThatThrownBy(() -> service.teach(CHAT, "R1", "n", tooLong, RiskLevel.MEDIUM, false, 42L))
                .isInstanceOf(TggException.class)
                .hasMessageContaining("过长");
    }

    /** ReDoS 闸门：嵌套量词在长文本上灾难性回溯。宁可误拒，不可让热路径被打爆。 */
    @Test
    void rejectsNestedQuantifier() {
        assertThatThrownBy(() -> service.teach(CHAT, "R1", "n", "(a+)+", RiskLevel.MEDIUM, false, 42L))
                .isInstanceOf(TggException.class)
                .hasMessageContaining("嵌套量词");
        assertThatThrownBy(() -> service.teach(CHAT, "R2", "n", "(a*)*$", RiskLevel.MEDIUM, false, 42L))
                .isInstanceOf(TggException.class);
    }

    @Test
    void acceptsSafeEquivalentOfNestedQuantifier() {
        // 等价但不嵌套的写法应放行——闸门是保守的，不该把正常规则也挡住
        when(repository.findByChatIdAndRuleId(CHAT, "SAFE")).thenReturn(Optional.empty());
        when(repository.save(any(TaughtRule.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.teach(CHAT, "SAFE", "描述", "a+", RiskLevel.MEDIUM, false, 42L)).isNotNull();
    }

    @Test
    void rejectsBlankOrWhitespaceRuleId() {
        assertThatThrownBy(() -> service.teach(CHAT, "  ", "n", "a", RiskLevel.MEDIUM, false, 42L))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> service.teach(CHAT, "A B", "n", "a", RiskLevel.MEDIUM, false, 42L))
                .as("规则 id 含空白会破坏命令参数切分")
                .isInstanceOf(TggException.class);
    }

    @Test
    void rejectsBlankNameAndNullRiskLevel() {
        assertThatThrownBy(() -> service.teach(CHAT, "R1", " ", "a", RiskLevel.MEDIUM, false, 42L))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> service.teach(CHAT, "R1", "n", "a", null, false, 42L))
                .isInstanceOf(TggException.class);
    }

    // ---------- 落库与幂等 ----------

    @Test
    void teachInsertsRuleForChat() {
        when(repository.findByChatIdAndRuleId(CHAT, "SCAM_AIRDROP")).thenReturn(Optional.empty());
        when(repository.save(any(TaughtRule.class))).thenAnswer(inv -> inv.getArgument(0));

        service.teach(CHAT, "SCAM_AIRDROP", "假空投", "免费空投\\d+", RiskLevel.MEDIUM, false, 42L);

        ArgumentCaptor<TaughtRule> captor = ArgumentCaptor.forClass(TaughtRule.class);
        verify(repository).save(captor.capture());
        TaughtRule saved = captor.getValue();
        assertThat(saved.getChatId()).isEqualTo(CHAT);
        assertThat(saved.getRuleId()).isEqualTo("SCAM_AIRDROP");
        assertThat(saved.getCreatedBy()).isEqualTo(42L);
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void retachingSameRuleIdRedefinesInsteadOfDuplicating() {
        TaughtRule existing = saved("R1", "old", RiskLevel.LOW, false);
        when(repository.findByChatIdAndRuleId(CHAT, "R1")).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        service.teach(CHAT, "R1", "新描述", "new\\d+", RiskLevel.HIGH, false, 42L);

        assertThat(existing.getRegex()).isEqualTo("new\\d+");
        assertThat(existing.getRiskLevel()).isEqualTo("HIGH");
        assertThat(existing.isEnabled()).as("重定义应重新启用").isTrue();
    }

    // ---------- 缓存 ----------

    @Test
    void rulesForHitsCacheWithinTtl() {
        when(repository.findByChatIdAndEnabledTrueOrderByIdAsc(CHAT))
                .thenReturn(List.of(saved("R1", "spam\\d+", RiskLevel.MEDIUM, false)));

        service.rulesFor(CHAT);
        service.rulesFor(CHAT);
        service.rulesFor(CHAT);

        verify(repository, times(1))
                .findByChatIdAndEnabledTrueOrderByIdAsc(CHAT);
    }

    @Test
    void teachInvalidatesCacheSoRuleTakesEffectImmediately() {
        when(repository.findByChatIdAndEnabledTrueOrderByIdAsc(CHAT))
                .thenReturn(List.of(saved("R1", "spam\\d+", RiskLevel.MEDIUM, false)));
        service.rulesFor(CHAT); // 预热缓存

        when(repository.findByChatIdAndRuleId(CHAT, "R2")).thenReturn(Optional.empty());
        when(repository.save(any(TaughtRule.class))).thenAnswer(inv -> inv.getArgument(0));
        service.teach(CHAT, "R2", "新规则", "new\\d+", RiskLevel.MEDIUM, false, 42L);
        service.rulesFor(CHAT);

        verify(repository, times(2))
                .findByChatIdAndEnabledTrueOrderByIdAsc(CHAT);
    }

    @Test
    void disableInvalidatesCacheAndReportsMissingRule() {
        when(repository.findByChatIdAndEnabledTrueOrderByIdAsc(CHAT)).thenReturn(List.of());
        service.rulesFor(CHAT);

        TaughtRule rule = saved("R1", "spam\\d+", RiskLevel.MEDIUM, false);
        when(repository.findByChatIdAndRuleId(CHAT, "R1")).thenReturn(Optional.of(rule));
        assertThat(service.disable(CHAT, "R1")).isTrue();
        assertThat(rule.isEnabled()).isFalse();

        assertThat(service.disable(CHAT, "不存在")).as("停用不存在的规则应返回 false 而非抛异常").isFalse();
    }

    // ---------- 坏数据不拖垮整群审核 ----------

    @Test
    void skipsUncompilableRuleFromDatabaseWithoutLosingOthers() {
        TaughtRule broken = saved("BROKEN", "([", RiskLevel.HIGH, false);
        TaughtRule good = saved("GOOD", "spam\\d+", RiskLevel.MEDIUM, false);
        when(repository.findByChatIdAndEnabledTrueOrderByIdAsc(CHAT)).thenReturn(List.of(broken, good));

        List<ModerationRule> rules = service.rulesFor(CHAT);

        assertThat(rules).as("坏规则被跳过，好规则照常生效").hasSize(1);
        assertThat(rules.get(0).id()).isEqualTo("GOOD");
    }

    @Test
    void listTaughtReturnsAllIncludingDisabled() {
        when(repository.findByChatIdOrderByIdAsc(CHAT)).thenReturn(List.of(saved("R1", "a", RiskLevel.LOW, false)));

        assertThat(service.listTaught(CHAT)).hasSize(1);
        verify(repository).findByChatIdOrderByIdAsc(CHAT);
    }

    @Test
    void validateRegexTrimsSurroundingWhitespace() {
        assertThat(TaughtRuleService.validateRegex("  spam\\d+  ")).isEqualTo("spam\\d+");
    }

    @Test
    void teachDoesNotTouchOtherChats() {
        when(repository.findByChatIdAndRuleId(anyLong(), anyString())).thenReturn(Optional.empty());
        when(repository.save(any(TaughtRule.class))).thenAnswer(inv -> inv.getArgument(0));

        service.teach(CHAT, "R1", "n", "a", RiskLevel.MEDIUM, false, 42L);

        ArgumentCaptor<TaughtRule> captor = ArgumentCaptor.forClass(TaughtRule.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo(CHAT);
    }

    // ---------- 提交后审查抓到的两条契约断裂（回归护栏）----------

    /** 反例一回归：描述是自由文本，含空格必须被接受（命令层就是把剩余参数整体当描述）。 */
    @Test
    void acceptsMultiWordDescription() {
        when(repository.findByChatIdAndRuleId(CHAT, "R1")).thenReturn(Optional.empty());
        when(repository.save(any(TaughtRule.class))).thenAnswer(inv -> inv.getArgument(0));

        TaughtRule saved = service.teach(CHAT, "R1", "假空投 骗局 描述含空格", "a+",
                RiskLevel.MEDIUM, false, 42L);

        assertThat(saved.getName()).isEqualTo("假空投 骗局 描述含空格");
    }

    /** 反例二回归：库里的 hard_line 必须传导进热路径值对象（该列曾被静默丢弃、写完即无人消费）。 */
    @Test
    void hardLineFlagReachesHotPathRule() {
        when(repository.findByChatIdAndEnabledTrueOrderByIdAsc(CHAT))
                .thenReturn(List.of(saved("HARD", "红线", RiskLevel.HIGH, true)));

        List<ModerationRule> rules = service.rulesFor(CHAT);

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).hardLine())
                .as("库里 hard_line=true 的规则在热路径上必须仍是硬红线")
                .isTrue();
    }

    /**
     * 贯通护栏：教一条硬红线规则 → 检测器命中时报 hardLine。
     *
     * <p>加它的理由正是审查的第三条发现——上面两条契约断裂都能在「全绿的单测」下存活，
     * 因为此前没有任何用例把「库里的规则」一路走到「检测结果」。
     */
    @Test
    void taughtHardLineRuleIsDetectedAsHardLineEndToEnd() {
        when(repository.findByChatIdAndEnabledTrueOrderByIdAsc(CHAT))
                .thenReturn(List.of(saved("HARD", "红线", RiskLevel.HIGH, true)));
        TaughtRuleDetector detector = new TaughtRuleDetector(service);

        ModerationVerdict verdict = detector.inspect(CHAT, "红线内容").orElseThrow();

        assertThat(verdict.hardLine()).isTrue();
        assertThat(verdict.matchedRuleIds()).containsExactly("HARD");
    }
}

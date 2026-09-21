package com.tg.heyisheng.bot.core.groupconfig;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository;
import com.tg.heyisheng.bot.core.moderation.ReviewStatus;
import com.tg.heyisheng.bot.core.wordfilter.BannedWordService;
import com.tg.heyisheng.bot.core.wordfilter.TaughtRuleService;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /status} 的状态概览。
 *
 * <p>重点两条：① 只给**数量**不给内容（违禁词内容要 MANAGE_CONFIG，这里是公开命令）；
 * ② 待复核数取**本群**而非全局——本命令回复对整个群可见，全局数属平台面信息。
 */
class StatusCommandHandlerTest {

    private static final long CHAT = -100L;

    private final GroupConfigService groups = mock(GroupConfigService.class);
    private final BannedWordService bannedWords = mock(BannedWordService.class);
    private final TaughtRuleService taughtRules = mock(TaughtRuleService.class);
    private final ModerationReviewRepository reviews = mock(ModerationReviewRepository.class);

    private String textOf(boolean groupEnabled, int words, int rules, long pending) {
        when(groups.findOrDefault(CHAT)).thenReturn(new GroupConfigView(CHAT, "群", groupEnabled));
        when(bannedWords.listWords(CHAT)).thenReturn(java.util.stream.IntStream.range(0, words)
                .mapToObj(i -> "词" + i).toList());
        when(taughtRules.listTaught(CHAT)).thenReturn(java.util.stream.IntStream.range(0, rules)
                .mapToObj(i -> mock(com.tg.heyisheng.bot.core.wordfilter.TaughtRule.class)).toList());
        when(reviews.countByChatIdAndStatus(CHAT, ReviewStatus.PENDING)).thenReturn(pending);

        SendMessage reply = (SendMessage) new StatusCommandHandler(groups, bannedWords, taughtRules, reviews)
                .handle(new UpdateContext(1, 42L, CHAT, 9, "status"));
        return reply.getText();
    }

    @Test
    void showsEnabledStateAndCounts() {
        String text = textOf(true, 3, 2, 5);

        assertThat(text).contains("已启用");
        assertThat(text).contains("违禁词：3 条");
        assertThat(text).contains("教学规则：2 条");
        assertThat(text).contains("待复核案件：5 条");
    }

    /** 停用态要给出去路（VOICE.md 的 R2：拒绝必须给出路），否则管理员只看到「已停用」而不知怎么办。 */
    @Test
    void disabledStatePointsToTheRecoveryCommand() {
        String text = textOf(false, 0, 0, 0);

        assertThat(text).contains("已停用").contains("/enable");
    }

    /** ★ 只给数量不给内容：本命令对全体成员可见，回显词表内容就成了绕过 MANAGE_CONFIG 的后门。 */
    @Test
    void neverLeaksWordContent() {
        String text = textOf(true, 3, 0, 0);

        assertThat(text).as("泄露词表内容即等于绕过权限门控").doesNotContain("词0").doesNotContain("词1");
    }

    /** ★ 待复核取本群维度，不得退化成全局计数（回复全群可见）。 */
    @Test
    void pendingCountIsScopedToThisChat() {
        textOf(true, 0, 0, 7);

        verify(reviews).countByChatIdAndStatus(CHAT, ReviewStatus.PENDING);
        verify(reviews, never()).countByStatus(any());
    }

    /** 命令元数据：自助命令（对全体成员可见）。 */
    @Test
    void commandIsPublicSelfService() {
        com.tg.heyisheng.bot.core.dispatch.BotCommand meta =
                StatusCommandHandler.class.getAnnotation(com.tg.heyisheng.bot.core.dispatch.BotCommand.class);

        assertThat(meta).isNotNull();
        assertThat(meta.value()).isEqualTo("status");
        assertThat(meta.publicCommand()).isTrue();
        assertThat(meta.description()).isNotBlank();
    }
}

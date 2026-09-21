package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /case_appeal} 的行为与**安全边界**。
 *
 * <p>本类的重点不是"能提交成功"，而是**只有当事人能提交**：案件号在群内是公开的
 * （删除告知里带着编号），所以「知道编号」不等于「有权申诉」。若不校验，
 * 任何人都能用别人的编号把申诉队列灌满。
 */
class CaseAppealCommandHandlerTest {

    private static final long CHAT = -100L;
    private static final long PARTY = 42L;      // 案件当事人
    private static final long STRANGER = 77L;   // 知道编号的旁人
    private static final long CASE_ID = 7L;

    private final ModerationReviewRepository reviews = mock(ModerationReviewRepository.class);
    private final CaseAppealRepository appeals = mock(CaseAppealRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC);

    private CaseAppealCommandHandler handler() {
        return new CaseAppealCommandHandler(reviews, appeals, clock);
    }

    /** 案件 #7 的当事人是 PARTY。 */
    private void givenCaseExists() {
        when(reviews.findById(CASE_ID)).thenReturn(Optional.of(
                new ModerationReviewItem(CHAT, PARTY, 5, List.of("SPAM_CASINO"),
                        RiskLevel.LOW, false)));
    }

    private String textOf(Long userId, String args) {
        BotApiMethod<?> reply = handler().handle(
                new UpdateContext(1, userId, CHAT, 9, "case_appeal", args));
        return ((SendMessage) reply).getText();
    }

    @Test
    void partyCanSubmitAndItIsPersisted() {
        givenCaseExists();
        when(appeals.findByReviewIdAndUserId(CASE_ID, PARTY)).thenReturn(Optional.empty());
        when(appeals.save(any(CaseAppeal.class))).thenAnswer(inv -> inv.getArgument(0));

        String text = textOf(PARTY, "7 那条是正常讨论，不是广告");

        assertThat(text).as("回执要给出申诉编号，当事人才能追踪").contains("已提交");
        assertThat(text).as("并回指案件号，避免申诉错案").contains("案件 #7");
        verify(appeals).save(any(CaseAppeal.class));
    }

    /** ★ 安全边界：知道编号 ≠ 有权申诉。 */
    @Test
    void strangerCannotAppealEvenThoughCaseIdIsPublic() {
        givenCaseExists();

        String text = textOf(STRANGER, "7 我替他申诉");

        assertThat(text).isEqualTo(CaseAppealCommandHandler.NOT_THE_PARTY);
        verify(appeals, never()).save(any(CaseAppeal.class));
    }

    @Test
    void unknownCaseIsReportedWithoutCreatingAnything() {
        when(reviews.findById(CASE_ID)).thenReturn(Optional.empty());

        String text = textOf(PARTY, "7 我的理由");

        assertThat(text).contains("未找到案件 #7");
        verify(appeals, never()).save(any(CaseAppeal.class));
    }

    /** 幂等：同一案件同一人重复提交，不产生第二条。 */
    @Test
    void duplicateSubmissionIsIdempotent() {
        givenCaseExists();
        when(appeals.findByReviewIdAndUserId(CASE_ID, PARTY)).thenReturn(Optional.of(
                new CaseAppeal(CASE_ID, PARTY, "上次的理由", clock.instant())));

        String text = textOf(PARTY, "7 再申诉一次");

        assertThat(text).contains("提交过申诉");
        verify(appeals, never()).save(any(CaseAppeal.class));
    }

    @Test
    void malformedInputFallsBackToUsage() {
        for (String bad : new String[]{"", "7", "abc 理由", "7   ", "   "}) {
            assertThat(textOf(PARTY, bad))
                    .as("输入不合法应回用法（可照抄的示例）：%s", bad)
                    .isEqualTo(CaseAppealCommandHandler.USAGE);
        }
    }

    @Test
    void overlongReasonIsRejectedBeforePersisting() {
        givenCaseExists();
        String tooLong = "7 " + "理由".repeat(CaseAppealCommandHandler.MAX_REASON_CHARS);

        assertThat(textOf(PARTY, tooLong)).contains("理由太长");
        verify(appeals, never()).save(any(CaseAppeal.class));
    }

    @Test
    void missingIdentityIsReportedWithoutTouchingStorage() {
        assertThat(textOf(null, "7 理由"))
                .isEqualTo(CaseAppealCommandHandler.NOT_A_GROUP_MEMBER_IDENTITY);
        verify(appeals, never()).save(any(CaseAppeal.class));
        verify(reviews, never()).findById(any());
    }

    /** 命令元数据：自助命令（对全体成员可见），否则 CommandMenuContentTest 的 fail-closed 会判它「对谁都不可见」。 */
    @Test
    void commandIsPublicSelfService() {
        BotCommand meta = CaseAppealCommandHandler.class.getAnnotation(BotCommand.class);

        assertThat(meta).isNotNull();
        assertThat(meta.value()).isEqualTo("case_appeal");
        assertThat(meta.publicCommand()).isTrue();
        assertThat(meta.description()).isNotBlank();
        assertThat(meta.category()).isEqualTo(MenuCategory.SELF_SERVICE);
    }
}

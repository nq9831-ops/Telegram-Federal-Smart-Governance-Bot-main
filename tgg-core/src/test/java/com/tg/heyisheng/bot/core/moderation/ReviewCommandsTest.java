package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复核命令单测（模块九 §10.4）。
 *
 * <p><b>守两件事</b>：① <b>非复核人静默</b>——回复「权限不足」等于向无权者确认命令存在；
 * ② 命令把「谁裁决的」如实传给服务层——审计字段的来源就在这里，传错等于审计失真。
 */
class ReviewCommandsTest {

    private static final long REVIEWER = 900L;
    private static final long OUTSIDER = 901L;
    private static final long CHAT = -100900600L;

    private final ModerationReviewDecisionService decisions = mock(ModerationReviewDecisionService.class);
    private final ModerationReviewGuard guard = new ModerationReviewGuard(String.valueOf(REVIEWER));

    private static UpdateContext ctx(long user, String args) {
        return new UpdateContext(1, user, CHAT, 5, "review_approve", args);
    }

    private static String text(Object reply) {
        return ((SendMessage) reply).getText();
    }

    private static ModerationReviewDecisionService.Outcome outcome(
            ModerationReviewDecisionService.Outcome.Result result, ReviewStatus status) {
        return new ModerationReviewDecisionService.Outcome(result, status);
    }

    @Test
    void nonReviewerGetsSilence() {
        assertThat(new ReviewApproveCommandHandler(decisions, guard).handle(ctx(OUTSIDER, "7")))
                .as("非复核人应静默（不是「权限不足」）").isNull();
        assertThat(new ReviewRejectCommandHandler(decisions, guard).handle(ctx(OUTSIDER, "7"))).isNull();
        assertThat(new ReviewListCommandHandler(decisions, guard).handle(ctx(OUTSIDER, null))).isNull();
    }

    @Test
    void approveDecidesAsApprovedForOperator() {
        when(decisions.decide(7L, ReviewStatus.APPROVED, REVIEWER, "确认"))
                .thenReturn(outcome(ModerationReviewDecisionService.Outcome.Result.DECIDED, ReviewStatus.APPROVED));

        String reply = text(new ReviewApproveCommandHandler(decisions, guard).handle(ctx(REVIEWER, "7 确认")));

        assertThat(reply).contains("#7").contains("维持").contains("APPROVED");
        verify(decisions).decide(7L, ReviewStatus.APPROVED, REVIEWER, "确认");
    }

    @Test
    void rejectDecidesAsRejected() {
        when(decisions.decide(7L, ReviewStatus.REJECTED, REVIEWER, null))
                .thenReturn(outcome(ModerationReviewDecisionService.Outcome.Result.DECIDED, ReviewStatus.REJECTED));

        String reply = text(new ReviewRejectCommandHandler(decisions, guard).handle(ctx(REVIEWER, "7")));

        assertThat(reply).contains("推翻");
        verify(decisions).decide(7L, ReviewStatus.REJECTED, REVIEWER, null);
    }

    @Test
    void alreadyDecidedIsReportedIdempotently() {
        when(decisions.decide(7L, ReviewStatus.APPROVED, REVIEWER, null))
                .thenReturn(outcome(ModerationReviewDecisionService.Outcome.Result.ALREADY_DECIDED, ReviewStatus.REJECTED));

        String reply = text(new ReviewApproveCommandHandler(decisions, guard).handle(ctx(REVIEWER, "7")));

        assertThat(reply).contains("终态").contains("REJECTED");
    }

    @Test
    void unknownIdIsReported() {
        when(decisions.decide(7L, ReviewStatus.APPROVED, REVIEWER, null))
                .thenReturn(outcome(ModerationReviewDecisionService.Outcome.Result.NOT_FOUND, null));

        assertThat(text(new ReviewApproveCommandHandler(decisions, guard).handle(ctx(REVIEWER, "7"))))
                .contains("未找到");
    }

    @Test
    void badArgsShowUsage() {
        assertThat(text(new ReviewApproveCommandHandler(decisions, guard).handle(ctx(REVIEWER, "abc"))))
                .contains("用法");
    }

    @Test
    void listShowsPendingItems() {
        ModerationReviewItem item = new ModerationReviewItem(CHAT, 42L, 5, List.of("SPAM_LUCKY"),
                RiskLevel.MEDIUM, false);
        when(decisions.listPending()).thenReturn(List.of(item));

        String reply = text(new ReviewListCommandHandler(decisions, guard).handle(ctx(REVIEWER, null)));

        assertThat(reply).contains("待复核").contains("SPAM_LUCKY").contains("MEDIUM");
    }

    @Test
    void listReportsEmptyQueue() {
        when(decisions.listPending()).thenReturn(List.of());

        assertThat(text(new ReviewListCommandHandler(decisions, guard).handle(ctx(REVIEWER, null))))
                .contains("没有待复核");
    }

    @Test
    void guardParsesWhitelistAndRejectsUnknown() {
        ModerationReviewGuard g = new ModerationReviewGuard(" 900 , bad , 902 ");
        assertThat(g.size()).as("非数字项被忽略").isEqualTo(2);
        assertThat(g.isReviewer(902L)).isTrue();
        assertThat(g.isReviewer(null)).isFalse();
        assertThat(new ModerationReviewGuard("").isReviewer(900L))
                .as("空名单 = 无人可复核").isFalse();
    }
}

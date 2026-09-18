package com.tg.heyisheng.bot.admin.approval;

import com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard;
import com.tg.heyisheng.bot.core.moderation.ReviewStatus;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import com.tg.heyisheng.bot.core.notify.Notification;
import com.tg.heyisheng.bot.core.notify.NotificationDispatcher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 审批超时提醒（模块十一 §12.1 第 4 步）。
 *
 * <p>最要紧的一条是<b>硬红线必须被跳过</b>：§10.6 已用 2 小时 SLA 催过它。
 * 若这里再催一遍，运营者会收到同一件事的两条提醒——很快他们就会把整类提醒静音，
 * 而那才是真正的失效。
 */
class ApprovalOverdueJobTest {

    private static final long REVIEWER = 777L;

    private final ApprovalQueryService queries = mock(ApprovalQueryService.class);
    private final ModerationReviewGuard guard = mock(ModerationReviewGuard.class);
    private final NotificationDispatcher notifications = mock(NotificationDispatcher.class);

    private final ApprovalOverdueJob job =
            new ApprovalOverdueJob(queries, guard, notifications, 24, 72);

    private static ApprovalQueryService.Item item(long id, RiskLevel level, boolean hardLine, long ageHours) {
        return new ApprovalQueryService.Item(id, -100L, 888L, "R1", level, hardLine,
                ReviewStatus.PENDING, Instant.now().minus(Duration.ofHours(ageHours)),
                null, null, null, ageHours, ageHours >= 24);
    }

    private void pending(ApprovalQueryService.Item... items) {
        when(queries.list(ReviewStatus.PENDING, 0, Integer.MAX_VALUE))
                .thenReturn(new ApprovalQueryService.Page(List.of(items), items.length, 0, items.length));
        when(guard.reviewerIds()).thenReturn(Set.of(REVIEWER));
    }

    private List<String> notifiedMessages() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, org.mockito.Mockito.atLeastOnce()).notify(captor.capture());
        return captor.getAllValues().stream().map(Notification::text).toList();
    }

    @Test
    void hardLineItemsAreSkippedBecauseTheSlaChannelAlreadyCoversThem() {
        pending(item(1, RiskLevel.HIGH, true, 100));

        job.run();

        verifyNoInteractions(notifications);
    }

    @Test
    void itemOverRemindThresholdIsReminded() {
        pending(item(2, RiskLevel.MEDIUM, false, 30));

        job.run();

        assertThat(notifiedMessages()).hasSize(1);
        assertThat(notifiedMessages().get(0)).contains("审批提醒").contains("#2");
    }

    @Test
    void itemOverEscalateThresholdIsEscalatedNotMerelyReminded() {
        pending(item(3, RiskLevel.MEDIUM, false, 100));

        job.run();

        assertThat(notifiedMessages()).hasSize(1);
        assertThat(notifiedMessages().get(0)).contains("审批升级").contains("#3");
    }

    @Test
    void freshItemsProduceNoNotification() {
        when(queries.list(ReviewStatus.PENDING, 0, Integer.MAX_VALUE))
                .thenReturn(new ApprovalQueryService.Page(
                        List.of(item(4, RiskLevel.LOW, false, 1)), 1, 0, 1));

        job.run();

        verifyNoInteractions(notifications);
    }

    @Test
    void emptyWhitelistWarnsInsteadOfSilentlyDoingNothing() {
        when(queries.list(ReviewStatus.PENDING, 0, Integer.MAX_VALUE))
                .thenReturn(new ApprovalQueryService.Page(
                        List.of(item(5, RiskLevel.HIGH, false, 100)), 1, 0, 1));
        when(guard.reviewerIds()).thenReturn(Set.of());

        job.run();

        // 配了功能却没人可催：不能以「一切正常」的样子静默过去（不抛异常，但也不发通知）
        verify(notifications, never()).notify(any());
    }

    @Test
    void everyReviewerGetsTheNotification() {
        when(queries.list(ReviewStatus.PENDING, 0, Integer.MAX_VALUE))
                .thenReturn(new ApprovalQueryService.Page(
                        List.of(item(6, RiskLevel.HIGH, false, 100)), 1, 0, 1));
        when(guard.reviewerIds()).thenReturn(Set.of(777L, 888L));

        job.run();

        verify(notifications, org.mockito.Mockito.times(2)).notify(any());
    }
}

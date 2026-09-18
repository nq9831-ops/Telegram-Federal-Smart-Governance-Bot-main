package com.tg.heyisheng.bot.core.breach;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard;
import com.tg.heyisheng.bot.core.notify.Notification;
import com.tg.heyisheng.bot.core.notify.NotificationDispatcher;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 72 小时泄露通报测试（模块十 §11.2）。
 *
 * <p>守四件事：① 截止＝发现 + <b>72h</b>（这是本特性的全部意义）；
 * ② 首次通报时间<b>不被覆盖</b>（那是合规证据）；③ 催办只在临近/逾期时发出；
 * ④ 催办走<b>紧急级</b>（不能被免打扰压住）且发给全部平台白名单成员。
 */
class DataBreachTest {

    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");
    private static final long REVIEWER = 900L;

    private final DataBreachRepository repository = mock(DataBreachRepository.class);
    private final ModerationReviewGuard guard = new ModerationReviewGuard(String.valueOf(REVIEWER));
    private final NotificationDispatcher notifications = mock(NotificationDispatcher.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private DataBreachService service() {
        return new DataBreachService(repository, clock);
    }

    private static UpdateContext ctx(long userId, String args) {
        return new UpdateContext(1, userId, -100900999L, 5, "data_breach", args);
    }

    private static String text(Object reply) {
        return ((SendMessage) reply).getText();
    }

    private static DataBreachIncident incident(long id, Instant deadline) {
        DataBreachIncident created = new DataBreachIncident(NOW, deadline, "审计表被未授权导出", 120, 1L, NOW);
        return created;
    }

    @Test
    void registerStartsSeventyTwoHourClock() {
        when(repository.save(any(DataBreachIncident.class))).thenAnswer(inv -> inv.getArgument(0));

        DataBreachIncident registered = service().register(null, "审计表被未授权导出", 120, 9L);

        assertThat(registered.getDeadlineAt()).as("72 小时是本特性的全部意义").isEqualTo(NOW.plus(Duration.ofHours(72)));
        assertThat(registered.isReported()).isFalse();
    }

    @Test
    void registerRejectsBlankScope() {
        assertThatThrownBy(() -> service().register(NOW, "   ", 1, 9L))
                .isInstanceOf(TggException.class);
    }

    @Test
    void markReportedIsIdempotentAndKeepsFirstTimestamp() {
        DataBreachIncident existing = incident(5L, NOW.plus(Duration.ofHours(72)));
        when(repository.findById(5L)).thenReturn(Optional.of(existing));

        assertThat(service().markReported(5L, REVIEWER)).isTrue();
        assertThat(existing.getReportedAt()).isNotNull();
        assertThat(existing.getReportedBy()).isEqualTo(REVIEWER);
        assertThat(service().markReported(5L, 999L))
                .as("重复标记不得覆盖首次通报（那是合规证据）").isFalse();
        assertThat(existing.getReportedBy()).isEqualTo(REVIEWER);
    }

    @Test
    void dueForReminderOnlyPicksNearOrOverdue() {
        DataBreachIncident soon = incident(1L, NOW.plus(Duration.ofHours(10)));  // 剩 10h → 催
        DataBreachIncident overdue = incident(2L, NOW.minus(Duration.ofHours(1))); // 已逾期 → 催
        DataBreachIncident far = incident(3L, NOW.plus(Duration.ofHours(70)));    // 剩 70h → 不催
        when(repository.findByReportedAtIsNullOrderByIdAsc()).thenReturn(List.of(soon, overdue, far));

        assertThat(service().dueForReminder()).containsExactly(soon, overdue);
    }

    @Test
    void commandIsSilentForNonReviewers() {
        DataBreachCommandHandler handler = new DataBreachCommandHandler(service(), guard);

        assertThat(handler.handle(ctx(REVIEWER + 1, null)))
                .as("非白名单成员应静默（回复「权限不足」等于确认命令存在）").isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void commandRegistersAndListsPending() {
        when(repository.save(any(DataBreachIncident.class))).thenAnswer(inv -> inv.getArgument(0));
        DataBreachCommandHandler handler = new DataBreachCommandHandler(service(), guard);

        assertThat(text(handler.handle(ctx(REVIEWER, "审计表被未授权导出 120"))))
                .contains("已登记泄露事件").contains("72 小时");

        when(repository.findByReportedAtIsNullOrderByIdAsc())
                .thenReturn(List.of(incident(7L, NOW.plus(Duration.ofHours(72)))));
        assertThat(text(handler.handle(ctx(REVIEWER, null))))
                .as("未持久化的实体没有 id（本项目已记录的坑），故断言范围文本而非编号")
                .contains("尚未通报的数据泄露事件")
                .contains("审计表被未授权导出");
    }

    @Test
    void jobNotifiesAllReviewersWithUrgentLevel() {
        when(repository.findByReportedAtIsNullOrderByIdAsc())
                .thenReturn(List.of(incident(7L, NOW.minus(Duration.ofHours(2)))));
        ModerationReviewGuard twoReviewers = new ModerationReviewGuard(REVIEWER + "," + (REVIEWER + 1));

        new DataBreachJob(service(), twoReviewers, notifications).remind();

        verify(notifications, org.mockito.Mockito.times(2))
                .notify(any(Notification.class));
        verify(notifications, org.mockito.Mockito.times(2)).notify(org.mockito.ArgumentMatchers.argThat(
                n -> n.level() == com.tg.heyisheng.bot.core.notify.NotificationLevel.URGENT
                        && n.text().contains("审计表被未授权导出")));
    }

    @Test
    void jobIsSilentWhenNothingDue() {
        when(repository.findByReportedAtIsNullOrderByIdAsc()).thenReturn(List.of());

        new DataBreachJob(service(), guard, notifications).remind();

        verify(notifications, never()).notify(any(Notification.class));
    }
}

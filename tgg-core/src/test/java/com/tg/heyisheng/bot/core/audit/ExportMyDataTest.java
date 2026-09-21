package com.tg.heyisheng.bot.core.audit;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.notify.NotificationPreferenceService;
import com.tg.heyisheng.bot.core.notify.QuietHours;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据导出测试（模块十 §11.2）。
 *
 * <p><b>本类守的是隐私边界</b>：导出必须<b>只</b>包含请求者自己的数据。
 * 最容易写错的写法是「查出全部再过滤」——那样一旦过滤条件写错，
 * 就会把别人的审计记录发给他。故这里除了断言内容，还断言
 * <b>没有调用任何「查全部」的入口</b>。
 *
 * <p><b>主体是双条件</b>（{@link ActorType} + id）：后台账号 id 与 TG userId 数值空间重叠，
 * 单条件会串号。本类在 mock 层断言「用了双条件」；真实的跨类型隔离由
 * {@code AuditActorTypeIT}（真库）钉住。
 */
class ExportMyDataTest {

    private static final long USER = 777L;
    private static final long CHAT = -100900999L;

    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final NotificationPreferenceService preferences = mock(NotificationPreferenceService.class);
    private final ExportMyDataCommandHandler handler =
            new ExportMyDataCommandHandler(auditLogRepository, preferences);

    private static UpdateContext ctx(Long userId) {
        return new UpdateContext(1, userId, CHAT, 5, "export_my_data", null);
    }

    private static String text(Object reply) {
        return ((SendMessage) reply).getText();
    }

    @Test
    void exportsOnlyTheCallersOwnEntries() {
        when(auditLogRepository.findByActorTypeAndActorIdOrderByIdDesc(ActorType.TG_USER, USER))
                .thenReturn(List.of(new AuditEntry(ActorType.TG_USER, USER, "TeachCommandHandler#handle",
                        CHAT, null, AuditEntry.Outcome.SUCCESS, null,
                        Instant.parse("2026-09-22T00:00:00Z"))));
        when(preferences.quietHoursOf(USER)).thenReturn(new QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0)));

        String reply = text(handler.handle(ctx(USER)));

        assertThat(reply).contains("TeachCommandHandler#handle").contains("22:00-08:00");
        // 必须用**双条件**（类型 + id）：单条件会串到同 id 的后台账号记录
        verify(auditLogRepository).findByActorTypeAndActorIdOrderByIdDesc(ActorType.TG_USER, USER);
        verify(auditLogRepository, never()).findByOrderByIdDesc(org.mockito.ArgumentMatchers.any());
        verify(auditLogRepository, never()).findByOccurredAtAfterOrderByIdAsc(any());
    }

    @Test
    void worksWhenUserHasNoPreferenceAndNoAudit() {
        when(auditLogRepository.findByActorTypeAndActorIdOrderByIdDesc(ActorType.TG_USER, USER))
                .thenReturn(List.of());
        when(preferences.quietHoursOf(USER)).thenReturn(null);

        String reply = text(handler.handle(ctx(USER)));

        assertThat(reply).contains("未设置免打扰时段").contains("共 0 条");
    }

    @Test
    void truncatesToTelegramLimit() {
        List<AuditEntry> many = java.util.stream.IntStream.range(0, 300)
                .mapToObj(i -> new AuditEntry(ActorType.TG_USER, USER, "VeryLongCommandHandlerName#handle",
                        CHAT, null, AuditEntry.Outcome.SUCCESS, null,
                        Instant.parse("2026-09-22T00:00:00Z")))
                .toList();
        when(auditLogRepository.findByActorTypeAndActorIdOrderByIdDesc(ActorType.TG_USER, USER))
                .thenReturn(many);
        when(preferences.quietHoursOf(USER)).thenReturn(null);

        String reply = text(handler.handle(ctx(USER)));

        assertThat(reply.length())
                .as("回复不得超过 Telegram 单条上限（超长会整条发送失败）")
                .isLessThanOrEqualTo(ExportMyDataCommandHandler.TELEGRAM_TEXT_LIMIT);
        assertThat(reply).contains("已截断");
    }

    @Test
    void rejectsWhenUserIdentityMissing() {
        assertThat(text(handler.handle(ctx(null)))).contains("无法识别");
    }
}

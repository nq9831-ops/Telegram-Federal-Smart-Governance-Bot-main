package com.tg.heyisheng.bot.core.audit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计服务测试（模块十 §11.2）。
 *
 * <p>核心是<b>失败取舍</b>：审计写不进去时不得中断业务（只记 ERROR），但也不得静默——
 * 这条取舍已写进部署清单，需部署侧对 ERROR 告警。
 */
class AuditServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final AuditService service =
            new AuditService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void recordsEntryWithActorActionOutcome() {
        service.record(777L, "TeachCommandHandler#handle", -100900999L,
                AuditEntry.Outcome.SUCCESS, null);

        verify(repository).save(any(AuditEntry.class));
    }

    @Test
    void repositoryFailureDoesNotPropagate() {
        when(repository.save(any(AuditEntry.class))).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.record(777L, "X#handle", null, AuditEntry.Outcome.SUCCESS, null))
                .as("审计失败不得中断业务（降级为 ERROR 日志）")
                .doesNotThrowAnyException();
    }
}

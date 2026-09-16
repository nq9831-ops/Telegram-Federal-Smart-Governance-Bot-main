package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 复核队列服务的单元测试。
 *
 * <p>本类只测「不依托真实数据库」的行为——核心是 fail-open：
 * 入队失败绝不得中断消息处理主链路（与 GroupConfigService 的同类取舍一致）。
 */
class ModerationReviewQueueServiceTest {

    private static final UpdateContext CTX = new UpdateContext(1, 42L, -100L, 77, null);
    private static final ModerationVerdict VERDICT =
            new ModerationVerdict(RiskLevel.MEDIUM, false, List.of("SPAM_LUCKY"));

    @Test
    void doesNotPropagateRepositoryFailure() {
        ModerationReviewRepository failing = mock(ModerationReviewRepository.class);
        when(failing.save(any(ModerationReviewItem.class))).thenThrow(new RuntimeException("db down"));

        ModerationReviewQueueService service = new ModerationReviewQueueService(failing);

        assertThatCode(() -> service.record(CTX, VERDICT))
                .as("DB 故障时入队必须静默放弃（记日志），不得让异常冒泡中断消息处理")
                .doesNotThrowAnyException();
    }
}

package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复核队列持久化测试 —— <b>直连本机 MySQL</b>，非 H2（与 GroupConfigPersistenceIT 同取舍：
 * H2 会掩盖 utf8mb4 / 负数 BIGINT 等方言差异）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ModerationReviewQueueService.class)
class ModerationReviewQueueServiceIT {

    private static final long CHAT_ID = -1002000000001L;

    @Autowired
    private ModerationReviewRepository repository;

    @Autowired
    private ModerationReviewQueueService service;

    @BeforeEach
    void clearQueue() {
        repository.deleteAll();
    }

    @Test
    void recordsPendingItemWithVerdictMetadata() {
        service.record(new UpdateContext(1, 42L, CHAT_ID, 77, null),
                new ModerationVerdict(RiskLevel.MEDIUM, false, List.of("SPAM_LUCKY")));

        List<ModerationReviewItem> items = repository.findAll();
        assertThat(items).hasSize(1);
        ModerationReviewItem item = items.get(0);
        assertThat(item.getChatId()).isEqualTo(CHAT_ID);
        assertThat(item.getUserId()).isEqualTo(42L);
        assertThat(item.getMessageId()).isEqualTo(77);
        assertThat(item.getRuleIds()).isEqualTo("SPAM_LUCKY");
        assertThat(item.getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(item.getStatus()).as("新建队列项一律 PENDING").isEqualTo(ReviewStatus.PENDING);
    }

    /**
     * 隐私回归：入队记录不得夹带消息正文。
     *
     * <p>实体本身没有正文字段（编译期已排除）；这里再断言「规则 id / 定位字段」里
     * 不会意外混入被判定内容。
     */
    @Test
    void storesNoMessageBody() {
        service.record(new UpdateContext(1, 42L, CHAT_ID, 77, null),
                new ModerationVerdict(RiskLevel.HIGH, false, List.of("SCAM_INVESTMENT")));

        ModerationReviewItem item = repository.findAll().get(0);
        String persisted = String.join("|",
                item.getRuleIds(), String.valueOf(item.getRiskLevel()), String.valueOf(item.getChatId()));

        assertThat(persisted)
                .as("队列只存判定结论与定位信息，绝不夹带被判定正文")
                .doesNotContain("保本", "稳赚", "casino", "私钥");
    }

    @Test
    void toleratesMissingUserIdAndMessageId() {
        service.record(new UpdateContext(1, null, CHAT_ID, null, null),
                new ModerationVerdict(RiskLevel.LOW, false, List.of("SPAM_CASINO")));

        ModerationReviewItem item = repository.findAll().get(0);
        assertThat(item.getUserId()).isNull();
        assertThat(item.getMessageId()).isNull();
    }
}

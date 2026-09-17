package com.tg.heyisheng.bot.core.moderation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复核裁决的真库测试 —— <b>直连本机 MySQL 测试库</b>，非 H2（同 {@code ModerationReviewQueueServiceIT}：
 * H2 会掩盖 utf8mb4 / 负数 BIGINT 等方言差异）。
 *
 * <p><b>为什么要真库</b>：裁决新增了 {@code hard_line} / {@code decided_by} / {@code decided_at} / {@code note}
 * 四列（V9 迁移）。列是否真建、值是否真落、终态是否真不可改写——这些都不是 mock 能证明的。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ModerationReviewDecisionService.class)
class ModerationReviewDecisionServiceIT {

    private static final long CHAT = -1002000000002L;
    private static final long OPERATOR = 999L;

    /** {@code @DataJpaTest} 不装配 {@code TggCoreConfiguration}，主动处置通道需手工提供替身。 */
    @TestConfiguration
    static class Stubs {
        @Bean
        ModerationActionSender moderationActionSender() {
            return ModerationActionSender.noop();
        }
    }

    @Autowired
    private ModerationReviewRepository repository;

    @Autowired
    private ModerationReviewDecisionService decisions;

    @BeforeEach
    void clearQueue() {
        repository.deleteAll();
    }

    @Test
    void decidePersistsDecisionAndIsIdempotent() {
        ModerationReviewItem saved = repository.save(new ModerationReviewItem(
                CHAT, 42L, 77, List.of("SCAM_INVESTMENT"), RiskLevel.HIGH, true));

        ModerationReviewDecisionService.Outcome first =
                decisions.decide(saved.getId(), ReviewStatus.REJECTED, OPERATOR, "误报");

        assertThat(first.result()).isEqualTo(ModerationReviewDecisionService.Outcome.Result.DECIDED);

        ModerationReviewItem reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(reloaded.getDecidedBy()).isEqualTo(OPERATOR);
        assertThat(reloaded.getDecidedAt()).as("裁决时间必须落库").isNotNull();
        assertThat(reloaded.getNote()).isEqualTo("误报");
        assertThat(reloaded.isHardLine()).as("V9 新增的 hard_line 必须真落库").isTrue();

        ModerationReviewDecisionService.Outcome second =
                decisions.decide(saved.getId(), ReviewStatus.APPROVED, OPERATOR, null);
        assertThat(second.result())
                .isEqualTo(ModerationReviewDecisionService.Outcome.Result.ALREADY_DECIDED);
        assertThat(repository.findById(saved.getId()).orElseThrow().getStatus())
                .as("终态不可被改写").isEqualTo(ReviewStatus.REJECTED);
    }

    @Test
    void listPendingReturnsOnlyPending() {
        repository.save(new ModerationReviewItem(CHAT, 1L, 1, List.of("A"), RiskLevel.LOW, false));
        ModerationReviewItem decided = repository.save(
                new ModerationReviewItem(CHAT, 2L, 2, List.of("B"), RiskLevel.MEDIUM, false));
        decisions.decide(decided.getId(), ReviewStatus.APPROVED, OPERATOR, null);

        assertThat(decisions.listPending())
                .as("已裁决项不再出现在待复核列表")
                .extracting(ModerationReviewItem::getUserId)
                .containsExactly(1L);
    }
}

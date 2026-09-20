package com.tg.heyisheng.bot.admin.approval;

import com.tg.heyisheng.bot.core.config.dynamic.ConfigOverrideRepository;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewItem;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository;
import com.tg.heyisheng.bot.core.moderation.ReviewStatus;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审批中心查询侧（模块十一 §12.1）。
 *
 * <p>本类守的是<b>优先级次序</b>——它错得最安静：列表照样返回、UI 照样好看，
 * 只是最该先看的条目被压在后面。故三个键各有一条测试，且都带反例（更旧 / 乱序输入）。
 */
class ApprovalQueryServiceTest {

    private static final long CHAT = -100900999L;
    private static final long USER = 777L;
    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

    private final ModerationReviewRepository repository = mock(ModerationReviewRepository.class);
    private final RuntimeConfigService config = configWith();
    private final ApprovalQueryService service = new ApprovalQueryService(
            repository, Clock.fixed(NOW, ZoneOffset.UTC), config);

    /** 无覆盖的配置服务：解析落到 ConfigCatalog 默认（24h / 72h），与改造前的常量等价。 */
    private static RuntimeConfigService configWith(String... keyValues) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            environment.withProperty(keyValues[i], keyValues[i + 1]);
        }
        return new RuntimeConfigService(mock(ConfigOverrideRepository.class), environment,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private long nextId = 1;

    /**
     * 造一个未持久化的实体。
     *
     * <p>{@code id} 与 {@code createdAt} <b>必须反射写入</b>：前者由数据库生成（未持久化时为 null，
     * 而列表项用 {@code long} 接收会拆箱 NPE），后者在构造器里被写死为 {@code Instant.now()}，
     * 不注入就无法构造「两小时前入队」这类场景。项目在 {@code LESSONS.md} 坑 5 已记过同类取舍。
     */
    private ModerationReviewItem item(RiskLevel level, boolean hardLine, Instant createdAt) {
        ModerationReviewItem entity =
                new ModerationReviewItem(CHAT, USER, 1, List.of("R1"), level, hardLine);
        set(entity, "id", nextId++);
        set(entity, "createdAt", createdAt);
        return entity;
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = ModerationReviewItem.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("测试夹具无法写入字段 " + field, ex);
        }
    }

    private void pendingAre(ModerationReviewItem... items) {
        when(repository.findByStatusOrderByIdAsc(ReviewStatus.PENDING)).thenReturn(List.of(items));
    }

    @Test
    void hardLineItemsComeFirstEvenWhenTheyAreOlder() {
        ModerationReviewItem olderHardLine = item(RiskLevel.MEDIUM, true, NOW.minus(Duration.ofHours(50)));
        ModerationReviewItem newerHigh = item(RiskLevel.HIGH, false, NOW.minus(Duration.ofHours(1)));
        pendingAre(newerHigh, olderHardLine);

        List<ApprovalQueryService.Item> items = service.list(ReviewStatus.PENDING, 0, 20).items();

        assertThat(items).extracting(ApprovalQueryService.Item::id)
                .as("硬红线命中即已自动封禁——纵使更旧，也必须最先看")
                .containsExactly(olderHardLine.getId(), newerHigh.getId());
    }

    @Test
    void higherSeverityComesBeforeLower() {
        ModerationReviewItem low = item(RiskLevel.LOW, false, NOW.minus(Duration.ofHours(3)));
        ModerationReviewItem high = item(RiskLevel.HIGH, false, NOW.minus(Duration.ofHours(1)));
        ModerationReviewItem medium = item(RiskLevel.MEDIUM, false, NOW.minus(Duration.ofHours(2)));
        pendingAre(low, high, medium);

        List<ApprovalQueryService.Item> items = service.list(ReviewStatus.PENDING, 0, 20).items();

        assertThat(items).extracting(ApprovalQueryService.Item::riskLevel)
                .as("按字符串排会得到 MEDIUM > LOW > HIGH 的错误次序，故必须按 severity 排")
                .containsExactly(RiskLevel.HIGH, RiskLevel.MEDIUM, RiskLevel.LOW);
    }

    @Test
    void sameSeverityKeepsFifoOrderSoOldItemsDoNotStarve() {
        ModerationReviewItem newer = item(RiskLevel.MEDIUM, false, NOW.minus(Duration.ofHours(1)));
        ModerationReviewItem older = item(RiskLevel.MEDIUM, false, NOW.minus(Duration.ofHours(10)));
        pendingAre(newer, older);

        List<ApprovalQueryService.Item> items = service.list(ReviewStatus.PENDING, 0, 20).items();

        assertThat(items).extracting(ApprovalQueryService.Item::id)
                .as("同级必须先入先审，否则低等级条目会被源源不断的新条目饿死")
                .containsExactly(older.getId(), newer.getId());
    }

    @Test
    void paginationSlicesAndReportsTotal() {
        ModerationReviewItem high = item(RiskLevel.HIGH, false, NOW.minus(Duration.ofHours(3)));
        ModerationReviewItem medium = item(RiskLevel.MEDIUM, false, NOW.minus(Duration.ofHours(2)));
        ModerationReviewItem low = item(RiskLevel.LOW, false, NOW.minus(Duration.ofHours(1)));
        pendingAre(high, medium, low);

        ApprovalQueryService.Page page = service.list(ReviewStatus.PENDING, 1, 2);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).extracting(ApprovalQueryService.Item::id)
                .as("第二页只剩最低优先级那条")
                .containsExactly(low.getId());
    }

    @Test
    void pageBeyondTheEndIsEmptyRatherThanAnError() {
        ModerationReviewItem only = item(RiskLevel.LOW, false, NOW);
        pendingAre(only);

        ApprovalQueryService.Page page = service.list(ReviewStatus.PENDING, 5, 20);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    void overdueRespectsBothThresholdsAndTheyAreNotMutuallyExclusive() {
        // 已升级的条目必然也超过了提醒阈值——两者是累计计数，不是互斥分类
        pendingAre(
                item(RiskLevel.HIGH, false, NOW.minus(Duration.ofHours(100))),
                item(RiskLevel.MEDIUM, false, NOW.minus(Duration.ofHours(30))),
                item(RiskLevel.LOW, false, NOW.minus(Duration.ofHours(1))));

        ApprovalQueryService.Stats stats = service.stats();

        assertThat(stats.overdueRemind()).isEqualTo(2);
        assertThat(stats.overdueEscalate()).isEqualTo(1);
    }

    @Test
    void averageDecisionHoursIsNullWhenNothingHasBeenDecidedYet() {
        when(repository.averageDecisionSeconds()).thenReturn(null);

        assertThat(service.stats().avgDecisionHours())
                .as("null = 还没有已裁决条目；用 0 代替会被读成「秒批」")
                .isNull();
    }

    @Test
    void averageDecisionHoursIsConvertedFromSeconds() {
        when(repository.averageDecisionSeconds()).thenReturn(7200.0);

        assertThat(service.stats().avgDecisionHours()).isEqualTo(2.0);
    }

    @Test
    void findReturnsEmptyForUnknownId() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThat(service.find(404L)).isEmpty();
    }

    @Test
    void decidedItemsAreOrderedNewestFirstNotByPriority() {
        ModerationReviewItem old = item(RiskLevel.HIGH, true, NOW.minus(Duration.ofHours(50)));
        ModerationReviewItem recent = item(RiskLevel.LOW, false, NOW.minus(Duration.ofHours(1)));
        when(repository.findByStatusOrderByIdAsc(ReviewStatus.APPROVED))
                .thenReturn(List.of(old, recent));

        List<ApprovalQueryService.Item> items = service.list(ReviewStatus.APPROVED, 0, 20).items();

        assertThat(items).extracting(ApprovalQueryService.Item::id)
                .as("已裁决的看「最近处理了什么」比看优先级更有用")
                .containsExactly(recent.getId(), old.getId());
    }

    /**
     * 超时阈值是<b>热参数</b>：改配置后**无需重启**即生效。
     *
     * <p>这是「热生效」的判据——消费方在调用期现读配置，而不是在构造期把它捕获成常量。
     * 若把本类的构造器改回「收 Duration」，这条会红。
     */
    @Test
    void remindThresholdIsReadHotFromConfig() {
        pendingAre(item(RiskLevel.MEDIUM, false, NOW.minus(Duration.ofHours(30))));
        assertThat(service.stats().overdueRemind()).as("默认 24h：30h 前的条目算超时").isEqualTo(1);

        config.set("tgg.admin.overdue-remind-hours", "40", 42L);

        assertThat(service.stats().overdueRemind())
                .as("阈值改到 40h 后（不重启），30h 的条目不再算超时")
                .isZero();
    }
}

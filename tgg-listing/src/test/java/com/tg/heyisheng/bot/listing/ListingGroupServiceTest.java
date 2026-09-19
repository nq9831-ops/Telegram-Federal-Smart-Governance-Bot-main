package com.tg.heyisheng.bot.listing;

import com.tg.heyisheng.bot.listing.verification.GroupLinkVerifier;
import com.tg.heyisheng.bot.listing.verification.VerificationRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 「长期未成功验证」的挑选逻辑（模块五 §3.2 的补充告警）。
 *
 * <p><b>为什么需要它</b>：{@code ERROR} 既不改变业务状态、也不累加 {@code failCount}
 * （见 {@link ListingGroupService#recordOutcome} 的空分支），因此「探测层持续不可用」
 * （缺 {@code TGG_BOT_TOKEN} / 网络不通 / 被限流）的条目会**永远安静地**停在 ACTIVE ——
 * 没有任何流程会注意到它。而一直 {@code FAIL} 的条目会累积到 {@code SUSPENDED}，
 * 由既有流程处理。所以「ACTIVE 且长期没有验证成功」正好等价于「连续多轮 ERROR」。
 *
 * <p><b>基准的选取</b>：取 {@code lastVerifiedAt}；从未成功验证过的条目回落到
 * {@code createdAt}——否则「提交后一直没验成功」这类最该被发现的条目反而漏掉。
 *
 * <p><b>边界</b>：严格早于阈值才算 stale（「正好 N 天」不算），与 {@code isBefore} 语义一致。
 */
class ListingGroupServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ListingGroupService service = new ListingGroupService(
            mock(ListingGroupRepository.class),
            mock(VerificationRecordRepository.class),
            mock(GroupLinkVerifier.class),
            new ListingProperties(),
            CLOCK);

    @Test
    void picksOnlyEntriesWhoseLastSuccessIsTooOld() {
        ListingGroup stale = entry(1L, NOW.minusSeconds(days(5)), NOW.minusSeconds(days(30)));
        ListingGroup fresh = entry(2L, NOW.minusSeconds(days(1)), NOW.minusSeconds(days(30)));

        List<ListingGroup> result = service.staleAmong(List.of(stale, fresh), 3);

        assertThat(result).extracting(ListingGroup::getId).containsExactly(1L);
    }

    @Test
    void neverVerifiedFallsBackToCreatedAt() {
        ListingGroup neverVerifiedOld = entry(3L, null, NOW.minusSeconds(days(5)));
        ListingGroup justSubmitted = entry(4L, null, NOW.minusSeconds(days(1)));

        List<ListingGroup> result = service.staleAmong(List.of(neverVerifiedOld, justSubmitted), 3);

        assertThat(result).extracting(ListingGroup::getId).containsExactly(3L);
    }

    @Test
    void boundaryEqualToThresholdIsNotStale() {
        ListingGroup exactlyAtThreshold = entry(5L, NOW.minusSeconds(days(3)), NOW.minusSeconds(days(30)));

        assertThat(service.staleAmong(List.of(exactlyAtThreshold), 3)).isEmpty();
    }

    @Test
    void returnsEmptyWhenNothingIsStale() {
        ListingGroup fresh = entry(6L, NOW.minusSeconds(days(1)), NOW.minusSeconds(days(30)));

        assertThat(service.staleAmong(List.of(fresh), 3)).isEmpty();
    }

    @Test
    void preservesInputOrderSoCallersCanLogStableIds() {
        ListingGroup second = entry(20L, NOW.minusSeconds(days(9)), NOW.minusSeconds(days(30)));
        ListingGroup first = entry(10L, NOW.minusSeconds(days(8)), NOW.minusSeconds(days(30)));

        List<ListingGroup> result = service.staleAmong(List.of(second, first), 3);

        assertThat(result).extracting(ListingGroup::getId).containsExactly(20L, 10L);
    }

    private static long days(long n) {
        return n * 24L * 3600L;
    }

    private static ListingGroup entry(long id, Instant lastVerifiedAt, Instant createdAt) {
        ListingGroup group = new ListingGroup();
        ReflectionTestUtils.setField(group, "id", id);
        ReflectionTestUtils.setField(group, "lastVerifiedAt", lastVerifiedAt);
        ReflectionTestUtils.setField(group, "createdAt", createdAt);
        return group;
    }
}

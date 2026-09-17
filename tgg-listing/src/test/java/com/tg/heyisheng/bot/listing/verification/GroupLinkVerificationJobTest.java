package com.tg.heyisheng.bot.listing.verification;

import com.tg.heyisheng.bot.listing.ListingGroup;
import com.tg.heyisheng.bot.listing.ListingGroupRepository;
import com.tg.heyisheng.bot.listing.ListingGroupService;
import com.tg.heyisheng.bot.listing.ListingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证状态机测试（设计文档 §3.2 / §9 反证）。
 *
 * <p>三条核心断言都是「本模块最危险的失败模式」的守门人：
 * <ol>
 *   <li><b>探测失败（ERROR）不计入 {@code fail_count}</b>——用抛异常的替身探针证明；</li>
 *   <li><b>连续 3 次 FAIL 才失效</b>——注入时钟连打 2 次仍 ACTIVE，第 3 次才 SUSPENDED；</li>
 *   <li><b>OK 后 {@code fail_count} 归零</b>。</li>
 * </ol>
 *
 * <p>另有第四项：重试退避真的按配置发生（次数 + 间隔）——用注入的 {@link Sleeper} 替身断言，
 * 而不是把间隔配成 0 假装测过。
 */
class GroupLinkVerificationJobTest {

    private static final Instant T0 = Instant.parse("2026-09-17T03:00:00Z");
    private static final long CHAT_ID = -1001234567890L;
    private static final long LISTING_ID = 7L;

    /** 可推进的时钟：证明状态机用的是注入时钟（断言 suspendedAt 的确切时刻），且测试不必真等。 */
    static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** 可编程替身探针：默认按 {@code fallback} 返回，也可预置「抛异常」。 */
    static class StubVerifier implements GroupLinkVerifier {
        private final Deque<Object> script = new ArrayDeque<>();
        private VerificationResult fallback = VerificationResult.OK;
        private int calls;

        StubVerifier alwaysReturns(VerificationResult result) {
            this.fallback = result;
            return this;
        }

        StubVerifier throwsOnce(RuntimeException ex) {
            this.script.add(ex);
            return this;
        }

        @Override
        public VerificationResult verify(ListingGroup entry) {
            calls++;
            Object next = script.poll();
            if (next instanceof RuntimeException ex) {
                throw ex;
            }
            return next == null ? fallback : (VerificationResult) next;
        }
    }

    private ListingGroupRepository groups;
    private VerificationRecordRepository records;
    private StubVerifier verifier;
    private MutableClock clock;
    private final List<Duration> sleeps = new ArrayList<>();
    private ListingGroup entry;
    private GroupLinkVerificationJob job;

    @BeforeEach
    void setUp() {
        groups = mock(ListingGroupRepository.class);
        records = mock(VerificationRecordRepository.class);
        verifier = new StubVerifier();
        clock = new MutableClock(T0);

        when(groups.save(any(ListingGroup.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ListingProperties properties = new ListingProperties();
        properties.setFailThreshold(3);
        properties.setRetryTimes(2);
        properties.setRetryIntervalMinutes(5);

        entry = new ListingGroup(CHAT_ID, "https://t.me/+abcdef", "测试群", 42L, T0);
        ReflectionTestUtils.setField(entry, "id", LISTING_ID);
        when(groups.findByStatusOrderByIdAsc(ListingGroup.Status.ACTIVE.name()))
                .thenReturn(List.of(entry));

        ListingGroupService service = new ListingGroupService(groups, records, verifier, properties, clock);
        job = new GroupLinkVerificationJob(service, properties, duration -> sleeps.add(duration));
    }

    @Test
    void probeErrorDoesNotCountAsFailure() {
        // 探针抛网络异常 = 我们没得到可信结论 —— 绝不能被当成「群失效」
        verifier.throwsOnce(new java.io.UncheckedIOException("probe failed",
                new java.net.ConnectException("connection refused")));

        job.verifyAllActive();

        assertThat(entry.getFailCount()).as("探测失败不得推进失败计数").isZero();
        assertThat(entry.getStatus()).isEqualTo(ListingGroup.Status.ACTIVE.name());
        assertThat(entry.getLastVerifiedAt()).as("探测失败不算一次成功验证").isNull();
        assertThat(entry.getSuspendedAt()).isNull();

        // 但必须留痕：审计要能区分「群真失效」与「探针问题」
        ArgumentCaptor<VerificationRecord> captor = ArgumentCaptor.forClass(VerificationRecord.class);
        verify(records).save(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo(VerificationResult.ERROR.name());
        assertThat(captor.getValue().getListingId()).isEqualTo(LISTING_ID);

        // ERROR 不重试（重试是给 FAIL 的退避），所以一次都不该睡
        assertThat(sleeps).as("ERROR 不做退避重试").isEmpty();
    }

    @Test
    void twoFailuresStayActiveAndThirdSuspends() {
        verifier.alwaysReturns(VerificationResult.FAIL);

        job.verifyAllActive();
        assertThat(entry.getFailCount()).isEqualTo(1);
        assertThat(entry.getStatus()).isEqualTo(ListingGroup.Status.ACTIVE.name());

        clock.advance(Duration.ofDays(1));
        job.verifyAllActive();
        assertThat(entry.getFailCount()).isEqualTo(2);
        assertThat(entry.getStatus())
                .as("连续 2 次失败仍应保持 ACTIVE（阈值是 3）")
                .isEqualTo(ListingGroup.Status.ACTIVE.name());
        assertThat(entry.getSuspendedAt()).isNull();

        clock.advance(Duration.ofDays(1));
        job.verifyAllActive();
        assertThat(entry.getFailCount()).isEqualTo(3);
        assertThat(entry.getStatus()).isEqualTo(ListingGroup.Status.SUSPENDED.name());
        assertThat(entry.getSuspendedAt())
                .as("suspendedAt 取自注入时钟，而非系统时间")
                .isEqualTo(clock.instant());
    }

    @Test
    void successResetsFailCountAndStampsLastVerifiedAt() {
        verifier.alwaysReturns(VerificationResult.FAIL);
        job.verifyAllActive();                      // failCount = 1
        assertThat(entry.getFailCount()).isEqualTo(1);

        clock.advance(Duration.ofDays(1));
        verifier.alwaysReturns(VerificationResult.OK);
        job.verifyAllActive();

        assertThat(entry.getFailCount()).as("验证成功应清零失败计数").isZero();
        assertThat(entry.getLastVerifiedAt()).isEqualTo(clock.instant());
        assertThat(entry.getStatus()).isEqualTo(ListingGroup.Status.ACTIVE.name());
    }

    @Test
    void failureIsRetriedWithConfiguredBackoffBeforeBeingCounted() {
        verifier.alwaysReturns(VerificationResult.FAIL);

        job.verifyAllActive();

        assertThat(verifier.calls).as("首次 + 按配置重试 2 次").isEqualTo(3);
        assertThat(sleeps).as("退避间隔取自配置（5 分钟）")
                .containsExactly(Duration.ofMinutes(5), Duration.ofMinutes(5));
        assertThat(entry.getFailCount())
                .as("一轮验证只记一次失败（重试不额外计数）")
                .isEqualTo(1);
    }
}

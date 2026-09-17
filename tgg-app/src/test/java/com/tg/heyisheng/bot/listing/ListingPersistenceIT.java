package com.tg.heyisheng.bot.listing;

import com.tg.heyisheng.bot.listing.verification.GroupLinkVerifier;
import com.tg.heyisheng.bot.listing.verification.VerificationRecord;
import com.tg.heyisheng.bot.listing.verification.VerificationRecordRepository;
import com.tg.heyisheng.bot.listing.verification.VerificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块五收录库的真库持久化测试 —— 直连本机 MySQL（取舍同 {@code CreditPersistenceIT}：
 * 不用 H2，因为它掩盖方言差异与唯一键语义）。
 *
 * <p>覆盖 mock 测不到的三件事：
 * <ol>
 *   <li><b>V6 迁移在真实库上确实建了表</b>（不是只靠 {@code ddl-auto: validate} 不报错就推定）；</li>
 *   <li><b>{@code INSERT IGNORE} 的幂等</b>——重复收录同一个 {@code chat_id} 既<b>不抛异常</b>、
 *       也<b>不产生第二行</b>（这是「先查再存 + catch 唯一约束」写法会崩掉的场景）；</li>
 *   <li><b>失效是软删</b>——{@code SUSPENDED} 后行仍在库中、仍可查（可审计 / 可申诉），
 *       只是不再出现在 ACTIVE 集合里。</li>
 * </ol>
 *
 * <p>断言对象是<b>库中的行</b>：每次断言前 {@code flush + clear}，避免一级缓存把
 * 「实体内存状态」当成「库中状态」来蒙混过关。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ListingGroupService.class)
class ListingPersistenceIT {

    private static final long CHAT_ID = -900900001L;
    private static final String LINK_A = "https://t.me/+listingA";
    private static final String LINK_B = "https://t.me/+listingB";
    private static final Instant FIXED_NOW = Instant.parse("2026-09-17T03:00:00Z");

    /** {@code @DataJpaTest} 是切片测试，不扫描 {@code @Configuration}；依赖在此手工提供。 */
    @TestConfiguration
    static class Deps {

        @Bean
        GroupLinkVerifier groupLinkVerifier() {
            // 本 IT 只驱动状态机（直接喂结果），探针返回什么都无关紧要
            return entry -> VerificationResult.OK;
        }

        @Bean
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }

        @Bean
        ListingProperties listingProperties() {
            ListingProperties properties = new ListingProperties();
            properties.setFailThreshold(3);
            return properties;
        }
    }

    @Autowired
    private ListingGroupRepository groups;

    @Autowired
    private VerificationRecordRepository records;

    @Autowired
    private ListingGroupService service;

    @Autowired
    private TestEntityManager entityManager;

    @BeforeEach
    void clear() {
        records.deleteAll();
        groups.deleteAll();
    }

    @Test
    void v6MigrationCreatedListingTables() {
        // 查询能执行即证明 listing_groups / listing_verification_records 在真实库中存在
        assertThat(groups.findByStatusOrderByIdAsc(ListingGroup.Status.ACTIVE.name())).isEmpty();
        assertThat(records.count()).isZero();
    }

    @Test
    void repeatedSubmissionIsAbsorbedByIdempotentInsert() {
        assertThat(service.submit(CHAT_ID, LINK_A, "群A", 42L)).as("首次收录应真的建行").isTrue();

        assertThat(service.submit(CHAT_ID, LINK_B, "群B", 43L))
                .as("重复收录不得抛唯一约束异常，返回 false 表示被忽略")
                .isFalse();

        List<ListingGroup> all = groups.findAll();
        assertThat(all).as("同 chatId 只应有一行").hasSize(1);
        ListingGroup row = all.get(0);
        assertThat(row.getTitle()).as("重复提交不覆盖既有条目").isEqualTo("群A");
        assertThat(row.getInviteLink()).isEqualTo(LINK_A);
        assertThat(row.getStatus()).isEqualTo(ListingGroup.Status.ACTIVE.name());
        assertThat(row.getFailCount()).isZero();
    }

    @Test
    void failureThresholdSuspendsRowWithoutDeletingIt() {
        service.submit(CHAT_ID, LINK_A, "群A", 42L);
        Long listingId = groups.findAll().get(0).getId();
        ListingGroup row = groups.findById(listingId).orElseThrow();

        assertThat(service.recordOutcome(row, VerificationResult.FAIL)).as("第 1 次失败").isFalse();
        assertThat(service.recordOutcome(row, VerificationResult.FAIL)).as("第 2 次失败仍 ACTIVE").isFalse();
        assertThat(service.recordOutcome(row, VerificationResult.FAIL)).as("第 3 次达阈值 → 转 SUSPENDED").isTrue();

        entityManager.flush();
        entityManager.clear();

        ListingGroup reloaded = groups.findById(listingId).orElseThrow(
                () -> new AssertionError("软删：行必须仍然在库中（可审计 / 可申诉），绝不物理删"));
        assertThat(reloaded.getStatus()).isEqualTo(ListingGroup.Status.SUSPENDED.name());
        assertThat(reloaded.getFailCount()).isEqualTo(3);
        assertThat(reloaded.getSuspendedAt()).isEqualTo(FIXED_NOW);

        assertThat(groups.findByStatusOrderByIdAsc(ListingGroup.Status.ACTIVE.name()))
                .as("失效条目不再出现在待验证集合（对外不可见）")
                .isEmpty();
        assertThat(groups.findByStatusOrderByIdAsc(ListingGroup.Status.SUSPENDED.name()))
                .extracting(ListingGroup::getId)
                .containsExactly(listingId);

        assertThat(records.findByListingIdOrderByIdAsc(listingId))
                .as("每次验证都留一条记录")
                .extracting(VerificationRecord::getResult)
                .containsExactly("FAIL", "FAIL", "FAIL");
    }

    @Test
    void probeErrorLeavesListingUntouchedButLeavesTrace() {
        service.submit(CHAT_ID, LINK_A, "群A", 42L);
        Long listingId = groups.findAll().get(0).getId();
        ListingGroup row = groups.findById(listingId).orElseThrow();

        assertThat(service.recordOutcome(row, VerificationResult.ERROR))
                .as("探测失败不判失效")
                .isFalse();

        entityManager.flush();
        entityManager.clear();

        ListingGroup reloaded = groups.findById(listingId).orElseThrow();
        assertThat(reloaded.getFailCount()).as("ERROR 不计入 fail_count").isZero();
        assertThat(reloaded.getStatus()).isEqualTo(ListingGroup.Status.ACTIVE.name());
        assertThat(reloaded.getLastVerifiedAt()).as("ERROR 不是一次成功验证").isNull();

        assertThat(records.findByListingIdOrderByIdAsc(listingId))
                .as("探测失败仍必须留痕（运维要能区分探测问题与真失效）")
                .extracting(VerificationRecord::getResult)
                .containsExactly("ERROR");
    }
}

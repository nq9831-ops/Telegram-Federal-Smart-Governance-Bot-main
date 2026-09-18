package com.tg.heyisheng.bot.core.membership;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入群观察的真库测试（模块九 §10.3）——直连本机 MySQL 测试库，非 H2。
 *
 * <p>守两件在单测里<b>测不到</b>的事：
 * <ol>
 *   <li>V15 迁移是否真建了表、唯一键是否真生效；</li>
 *   <li>{@code INSERT IGNORE} 的「<b>已有行不覆盖</b>」语义是否真由数据库实现——
 *       这是「入群时长」门槛不被状态变化绕过的<b>唯一</b>保障，而它是 DB 行为，
 *       mock 掉仓库的单元测试证明了它。</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MemberJoinObservationPersistenceIT {

    private static final long CHAT = -1002000000005L;
    private static final long USER = 9101L;
    private static final Instant JOINED = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MemberJoinObservationRepository repository;

    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    @Test
    void insertIfAbsentKeepsTheEarliestObservedJoinTime() {
        assertThat(repository.insertIfAbsent(CHAT, USER, JOINED, JOINED)).isEqualTo(1);

        Instant muchLater = JOINED.plus(Duration.ofDays(200));
        assertThat(repository.insertIfAbsent(CHAT, USER, muchLater, muchLater))
                .as("已有行 → 被忽略（返回 0）")
                .isZero();

        assertThat(repository.findByChatIdAndUserId(CHAT, USER).orElseThrow().getJoinedAt())
                .as("后续状态变化（晋升/禁言）绝不能刷新入群时间——否则门槛被无声绕过")
                .isEqualTo(JOINED);
    }

    @Test
    void uniqueKeyPreventsDuplicateRowsPerMember() {
        repository.insertIfAbsent(CHAT, USER, JOINED, JOINED);
        repository.insertIfAbsent(CHAT, USER, JOINED, JOINED);

        assertThat(repository.findAll()).as("同群同人只有一行").hasSize(1);
    }

    @Test
    void leavingDeletesTheObservation() {
        repository.insertIfAbsent(CHAT, USER, JOINED, JOINED);

        assertThat(repository.deleteByChatIdAndUserId(CHAT, USER)).isEqualTo(1);
        assertThat(repository.findByChatIdAndUserId(CHAT, USER)).isEmpty();
    }

    @Test
    void deletingAnUnobservedMemberIsANoOp() {
        assertThat(repository.deleteByChatIdAndUserId(CHAT, USER + 1))
                .as("bot 从未观察到其入群 → 删 0 行，不是错误")
                .isZero();
    }

    @Test
    void retentionHelpersFilterByObservedAt() {
        repository.insertIfAbsent(CHAT, USER, JOINED, JOINED);
        repository.insertIfAbsent(CHAT, USER + 1, JOINED, Instant.parse("2030-01-01T00:00:00Z"));

        Instant cutoff = Instant.parse("2027-01-01T00:00:00Z");

        assertThat(repository.countByObservedAtBefore(cutoff)).isEqualTo(1);
        assertThat(repository.deleteByObservedAtBefore(cutoff)).isEqualTo(1);
        assertThat(repository.countByObservedAtBefore(cutoff)).isZero();
        assertThat(repository.findByChatIdAndUserId(CHAT, USER + 1))
                .as("未超期的那条不受影响")
                .isPresent();
    }
}

package com.tg.heyisheng.bot.credit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * 信用分流水仓库。
 *
 * <p>写入用 native {@code INSERT IGNORE}，与 {@link CreditScoreRepository#insertIfAbsent} 同一模式
 * ——<b>不用 catch 唯一约束异常</b>：Hibernate 在 flush 失败后 session 即不可用，同事务后续操作全崩
 * （本项目实测踩过，见 {@code docs/LESSONS.md}）。数据库侧的静默跳过才是可组合的幂等。
 *
 * <p>枚举以 {@code String} 传入（native query 认不了 {@code @Enumerated}），由服务层用
 * {@code name()} 转换。
 */
public interface CreditEventRecordRepository extends JpaRepository<CreditEventRecord, Long> {

    /**
     * 幂等写入一条流水。
     *
     * <p>{@code idempotencyKey} 为 {@code null} 时恒插入（MySQL 中 NULL 互不相等，不触发唯一约束）
     * ——即「无幂等键的事件不去重」，这是刻意的：键缺失意味着生产侧拿不到稳定的业务标识，
     * 宁可与既有行为一致（每次都记），也不要错杀真实事件。
     *
     * @return 受影响行数：1 = 本次写入；0 = 同键已存在（重复事件，调用方据此跳过扣分）
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT IGNORE INTO credit_events "
            + "(subject_type, subject_id, event_type, severity, hard_line, score_before, score_delta, "
            + " score_after, source, idempotency_key, occurred_at, recorded_at) "
            + "VALUES (:subjectType, :subjectId, :eventType, :severity, :hardLine, :scoreBefore, :scoreDelta, "
            + " :scoreAfter, :source, :idempotencyKey, :occurredAt, :recordedAt)", nativeQuery = true)
    int insertIfAbsent(@Param("subjectType") String subjectType,
                       @Param("subjectId") long subjectId,
                       @Param("eventType") String eventType,
                       @Param("severity") String severity,
                       @Param("hardLine") boolean hardLine,
                       @Param("scoreBefore") int scoreBefore,
                       @Param("scoreDelta") int scoreDelta,
                       @Param("scoreAfter") int scoreAfter,
                       @Param("source") String source,
                       @Param("idempotencyKey") String idempotencyKey,
                       @Param("occurredAt") Instant occurredAt,
                       @Param("recordedAt") Instant recordedAt);

    /**
     * 记账后回填真实分值。
     *
     * <p>写入时 {@code scoreAfter} 是先算的预测值；真正的扣分走
     * {@link CreditScoreRepository#applyDelta} 的数据库侧夹取（下界 0 / 上界 150），
     * 两者在「触底」等场景会不一致。回填保证流水记的是**实际**结果。
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE credit_events SET score_after = :scoreAfter WHERE idempotency_key = :key",
            nativeQuery = true)
    int updateScoreAfter(@Param("key") String idempotencyKey, @Param("scoreAfter") int scoreAfter);
}

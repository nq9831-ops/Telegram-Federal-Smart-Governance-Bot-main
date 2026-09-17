package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * 信用分账本仓库。
 *
 * <p>两个写操作都用 native 语句走数据库侧原子语义：
 * <ul>
 *   <li>{@link #insertIfAbsent} —— 首次记账时建行，命中唯一约束静默跳过（{@code INSERT IGNORE}），
 *       与 {@code BannedWordRepository.insertIgnore} 同一模式：<b>不用 catch 唯一约束异常</b>，
 *       因为 Hibernate 在 flush 失败后 session 即不可用，同事务后续操作全崩；</li>
 *   <li>{@link #applyDelta} —— 递减用 {@code GREATEST(min, LEAST(max, score + delta))} 做
 *       <b>数据库侧原子读改写</b>，避免"取出来-改-写回"的竞态（并发扣分丢更新）。</li>
 * </ul>
 *
 * <p>枚举以 {@code String} 传入（native query 认不了 {@code @Enumerated}），由服务层用
 * {@link CreditSubjectType#name()} 转换。
 */
public interface CreditScoreRepository extends JpaRepository<CreditScore, Long> {

    Optional<CreditScore> findBySubjectTypeAndSubjectId(CreditSubjectType subjectType, long subjectId);

    /**
     * 首次记账：该主体无账本行时插入初始分，已存在则静默跳过。
     *
     * @return 受影响行数：1 = 新建；0 = 已存在（被忽略）
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT IGNORE INTO credit_scores (subject_type, subject_id, score, updated_at) "
            + "VALUES (:subjectType, :subjectId, :initialScore, :now)", nativeQuery = true)
    int insertIfAbsent(@Param("subjectType") String subjectType,
                       @Param("subjectId") long subjectId,
                       @Param("initialScore") int initialScore,
                       @Param("now") Instant now);

    /**
     * 原子地应用分值增量，并夹在 {@code [minScore, maxScore]} 之间（下界防负）。
     *
     * @return 受影响行数：1 = 更新成功；0 = 该主体账本行不存在（调用前须先 {@link #insertIfAbsent}）
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE credit_scores "
            + "SET score = GREATEST(:minScore, LEAST(:maxScore, score + :delta)), updated_at = :now "
            + "WHERE subject_type = :subjectType AND subject_id = :subjectId", nativeQuery = true)
    int applyDelta(@Param("subjectType") String subjectType,
                   @Param("subjectId") long subjectId,
                   @Param("delta") int delta,
                   @Param("minScore") int minScore,
                   @Param("maxScore") int maxScore,
                   @Param("now") Instant now);
}

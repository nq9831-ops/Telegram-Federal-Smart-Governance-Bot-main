package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
     * 带**行锁**的读（{@code SELECT ... FOR UPDATE}）——专供「读前值 → 写流水 → 原子改分」这条链。
     *
     * <p><b>为什么需要它</b>：{@link #applyDelta} 保证的是<b>分值本身</b>在并发下不丢更新（库侧原子
     * 读改写），但**流水**的 {@code score_before} 是应用侧先读出来的。两个同主体事件并发时，两者可能
     * 读到同一个前值 ⇒ 流水里出现两行「前值相同」的记录，账本无法串成连续账（审计轨迹断裂）。
     *
     * <p><b>代价可控</b>：本方法只在<b>命中审核并改分</b>时调用（不是每条消息），且锁粒度是
     * 「该主体那一行」；同一主体并发改分才会排队，属预期串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CreditScore c WHERE c.subjectType = :subjectType AND c.subjectId = :subjectId")
    Optional<CreditScore> findForUpdate(@Param("subjectType") CreditSubjectType subjectType,
                                        @Param("subjectId") long subjectId);

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
     * **确保账本行存在，并对其取排他锁**（幂等；不改变已有分值）。
     *
     * <p><b>为什么不能用「INSERT IGNORE + SELECT FOR UPDATE」</b>：{@code INSERT IGNORE} 命中已存在行时
     * 只取<b>共享锁</b>（互不冲突），随后两个事务各自想把 S 升级为 X 就**互相等待成环 ⇒ 死锁**
     * （MySQL 实测报 {@code Deadlock found when trying to get lock}，一方被回滚）。
     * {@code ON DUPLICATE KEY UPDATE} 则在语句内直接取**排他锁**，从起点就串行化，不再有升级环。
     *
     * <p>{@code subject_id = subject_id} 是无副作用的占位更新（只为触发排他锁与「已存在」分支）。
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT INTO credit_scores (subject_type, subject_id, score, updated_at) "
            + "VALUES (:subjectType, :subjectId, :initialScore, :now) "
            + "ON DUPLICATE KEY UPDATE subject_id = subject_id", nativeQuery = true)
    void ensureRowLocked(@Param("subjectType") String subjectType,
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

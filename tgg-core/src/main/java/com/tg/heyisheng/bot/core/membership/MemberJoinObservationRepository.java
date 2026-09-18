package com.tg.heyisheng.bot.core.membership;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * 成员入群时间观察仓库（模块九 §10.3）。
 *
 * <p><b>写入用 DB 侧 {@code INSERT IGNORE}，不用 catch 唯一约束异常</b>：
 * 与 {@code BannedWordRepository.insertIgnore} / {@code SensitiveTopicStrikeRepository.upsertIncrement}
 * 同一取舍——flush 冲突会让 Hibernate session 不可用，同事务后续操作全崩。
 * 这里还需要它表达「<b>已有行不覆盖</b>」的语义：同一个 member 的后续状态变化
 * （晋升管理员、被禁言等同样触发 chat_member）**不得**刷新入群时间。
 */
public interface MemberJoinObservationRepository extends JpaRepository<MemberJoinObservation, Long> {

    /** 读某成员在本群的入群观察行（教学门槛的判据）。 */
    Optional<MemberJoinObservation> findByChatIdAndUserId(long chatId, long userId);

    /**
     * 记一次「观察到该成员在群」；已有行则**保持原有 {@code joined_at}** 不变。
     *
     * @return 受影响行数：1 = 新建；0 = 已存在（被忽略，入群时间未被刷新）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT IGNORE INTO member_join_observations (chat_id, user_id, joined_at, observed_at)
            VALUES (:chatId, :userId, :joinedAt, :observedAt)
            """)
    int insertIfAbsent(@Param("chatId") long chatId,
                       @Param("userId") long userId,
                       @Param("joinedAt") Instant joinedAt,
                       @Param("observedAt") Instant observedAt);

    /**
     * 成员退群时删除其观察行——这是「表内只保留当前在群成员」的实现（数据最小化）。
     *
     * @return 删除行数：0 = 本来就没有行（例如 bot 从未观察到其入群）
     */
    long deleteByChatIdAndUserId(long chatId, long userId);

    /** 保留策略用：最后一次写入早于给定时点的行数（只读报告）。 */
    long countByObservedAtBefore(Instant cutoff);

    /** 保留策略用：删除最后一次写入早于给定时点的观察行（返回删除行数）。 */
    long deleteByObservedAtBefore(Instant cutoff);
}

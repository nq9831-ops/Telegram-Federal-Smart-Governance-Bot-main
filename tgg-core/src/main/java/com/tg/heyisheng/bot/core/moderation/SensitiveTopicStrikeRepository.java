package com.tg.heyisheng.bot.core.moderation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * 敏感话题违规计数仓库（模块九 §10.5）。
 */
public interface SensitiveTopicStrikeRepository extends JpaRepository<SensitiveTopicStrike, Long> {

    /** 定位某用户在本群的计数行（递进处置的判据）。 */
    Optional<SensitiveTopicStrike> findByChatIdAndUserId(long chatId, long userId);

    /**
     * 原子累加一次（不存在则插入 1）。
     *
     * <p><b>为什么用 DB 侧 upsert，而不是「先查再存」或「catch 唯一约束异常」</b>：
     * 后者在 Hibernate 下会把 session 打成不可用——flush 失败后同事务的后续操作全部崩掉。
     * 本项目在违禁词的幂等写入上已经踩过这个坑，结论是「让冲突不产生异常」
     * （见 {@code BannedWordRepository.insertIgnore} 的既有取舍）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO sensitive_topic_strikes (chat_id, user_id, strike_count, last_at)
            VALUES (:chatId, :userId, 1, :at)
            ON DUPLICATE KEY UPDATE strike_count = strike_count + 1, last_at = :at
            """)
    void upsertIncrement(@Param("chatId") long chatId, @Param("userId") long userId, @Param("at") Instant at);

    /** 保留策略用：最近一次违规早于给定时点的行数（只读报告）。 */
    long countByLastAtBefore(Instant cutoff);

    /** 保留策略用：删除最近一次违规早于给定时点的计数行（返回删除行数）。 */
    long deleteByLastAtBefore(Instant cutoff);
}

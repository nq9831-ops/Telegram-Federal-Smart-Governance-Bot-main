package com.tg.heyisheng.bot.core.wordfilter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * 按群违禁词仓库。
 *
 * <p>热路径（每条消息都要按群取一次词表）需要按 chat_id 查询，故显式声明该方法——
 * 若走 {@code findAll} 再过滤，词表增长后会拖垮处理链。
 */
public interface BannedWordRepository extends JpaRepository<BannedWord, Long> {

    /** 取某群的全部违禁词。 */
    List<BannedWord> findByChatId(Long chatId);

    /**
     * 幂等插入：命中唯一约束 {@code (chat_id, word)} 时<b>静默跳过</b>（MySQL {@code INSERT IGNORE}）。
     *
     * <p><b>为什么不用 "先查再存 + catch 异常"</b>：那条路在真实数据库上走不通——
     * {@code saveAndFlush} 触发约束冲突时，Hibernate 的 session 会因 flush 失败而进入不可用状态，
     * 方法虽 catch 了异常，<b>同一事务里后续的任何查询/写入都会失败</b>
     * （实测报错：{@code Entry for 'BannedWord' has a null identifier (this can happen if the session
     * is flushed after an exception occurs)}）。把冲突交给数据库"忽略"处理，就不产生异常、也不污染 session。
     *
     * <p>{@code clearAutomatically = true}：本语句绕过 JPA 一级缓存，清缓存以免后续查询读到陈旧视图。
     *
     * @return 受影响行数：1 = 新增成功；0 = 该词已存在（被忽略）
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT IGNORE INTO banned_words (chat_id, word, created_by, created_at) "
            + "VALUES (:chatId, :word, :createdBy, :createdAt)", nativeQuery = true)
    int insertIgnore(@Param("chatId") Long chatId,
                     @Param("word") String word,
                     @Param("createdBy") Long createdBy,
                     @Param("createdAt") Instant createdAt);

    /** 删除某群的一条词，返回删除条数。 */
    long deleteByChatIdAndWord(Long chatId, String word);
}

package com.tg.heyisheng.bot.core.wordfilter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 按群违禁词仓库。
 *
 * <p>热路径（每条消息都要按群取一次词表）需要按 chat_id 查询，故显式声明该方法——
 * 若走 `findAll` 再过滤，词表增长后会拖垮处理链。
 */
public interface BannedWordRepository extends JpaRepository<BannedWord, Long> {

    /** 取某群的全部违禁词。 */
    List<BannedWord> findByChatId(Long chatId);

    /** 是否已存在（用于幂等添加）。 */
    boolean existsByChatIdAndWord(Long chatId, String word);

    /** 删除某群的一条词，返回删除条数。 */
    long deleteByChatIdAndWord(Long chatId, String word);
}

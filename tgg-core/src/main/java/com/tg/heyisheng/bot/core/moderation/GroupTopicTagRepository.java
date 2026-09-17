package com.tg.heyisheng.bot.core.moderation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 群组话题标签仓库（模块九 §10.5）。
 */
public interface GroupTopicTagRepository extends JpaRepository<GroupTopicTag, Long> {

    /** 某群的全部标签（入队顺序）。 */
    List<GroupTopicTag> findByChatIdOrderByIdAsc(long chatId);

    /** 精确定位单个标签（幂等添加 / 单独删除用）。 */
    Optional<GroupTopicTag> findByChatIdAndTag(long chatId, String tag);
}

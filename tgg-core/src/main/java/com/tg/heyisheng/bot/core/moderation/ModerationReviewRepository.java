package com.tg.heyisheng.bot.core.moderation;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 待人工复核队列的仓库。
 *
 * <p>本阶段只需保存与计数；按状态分页查询等后台需求（模块十一）出现时再加方法。
 */
public interface ModerationReviewRepository extends JpaRepository<ModerationReviewItem, Long> {
}

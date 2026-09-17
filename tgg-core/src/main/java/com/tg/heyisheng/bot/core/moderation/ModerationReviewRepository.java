package com.tg.heyisheng.bot.core.moderation;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 待人工复核队列的仓库。
 *
 * <p>本阶段只需保存与计数；按状态分页查询等后台需求（模块十一）出现时再加方法。
 */
public interface ModerationReviewRepository extends JpaRepository<ModerationReviewItem, Long> {

    /** 按状态查（复核列表与「待裁决」筛选用；id 升序 = 入队顺序）。 */
    java.util.List<ModerationReviewItem> findByStatusOrderByIdAsc(ReviewStatus status);

    /** 保留策略用：某状态下、创建早于给定时点的行数（只读报告）。 */
    long countByStatusAndCreatedAtBefore(ReviewStatus status, java.time.Instant cutoff);

    /** 保留策略用：删除某状态下、创建早于给定时点的行（返回删除行数）。 */
    long deleteByStatusAndCreatedAtBefore(ReviewStatus status, java.time.Instant cutoff);
}

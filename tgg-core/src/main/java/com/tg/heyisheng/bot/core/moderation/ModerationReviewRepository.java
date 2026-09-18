package com.tg.heyisheng.bot.core.moderation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    /**
     * §10.6 红线复核 SLA 用：<b>硬红线且仍待裁决</b>、且创建早于给定时点的条目。
     *
     * <p>条件必须同时锁死「硬红线」与「PENDING」——把普通条目或已裁决条目算进来会稀释催办。
     */
    java.util.List<ModerationReviewItem> findByHardLineTrueAndStatusAndCreatedAtBefore(
            ReviewStatus status, java.time.Instant cutoff);

    // ─────────────── 模块十一（审批中心）所需的只读查询 ───────────────
    // 注意：优先级排序**刻意不在此处下推到 SQL**。risk_level 以枚举名（LOW/MEDIUM/HIGH）存储，
    // 按字符串排序会得到 MEDIUM > LOW > HIGH 这种错误次序；正确次序由应用层按 severity() 组装
    // 复合比较器（见 tgg-admin 的 ApprovalQueryService）。这里只提供计数与聚合。

    /** 按状态计数（统计用）。 */
    long countByStatus(ReviewStatus status);

    /** 硬红线且处于某状态的条数（统计用：待办中有多少是「已自动封禁、等人复核」的）。 */
    long countByHardLineTrueAndStatus(ReviewStatus status);

    /**
     * 已裁决条目的<b>平均处置时长</b>（秒）；没有已裁决条目时返回 {@code null}。
     *
     * <p>用 native SQL 而非 JPQL：{@code TIMESTAMPDIFF} 不是 JPQL 标准函数
     * （与本项目其他 native 查询同一取舍——让数据库做它擅长的时间差，而不是拉全表回来算）。
     */
    @Query(nativeQuery = true, value = """
            SELECT AVG(TIMESTAMPDIFF(SECOND, created_at, decided_at))
            FROM moderation_review_queue
            WHERE status IN ('APPROVED', 'REJECTED') AND decided_at IS NOT NULL
            """)
    Double averageDecisionSeconds();
}

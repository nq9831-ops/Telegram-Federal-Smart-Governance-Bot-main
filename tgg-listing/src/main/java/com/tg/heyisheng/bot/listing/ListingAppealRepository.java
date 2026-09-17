package com.tg.heyisheng.bot.listing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 失效申诉仓库（模块五）。
 *
 * <p>只做追加与按条目检索：申诉的裁定（PENDING → APPROVED/REJECTED）属后续增量，
 * 本波落的是「提交并留存」这一步（设计文档 §10：异议期到点后的自动动作列为后续）。
 */
public interface ListingAppealRepository extends JpaRepository<ListingAppeal, Long> {

    /** 按收录条目取申诉（主键序 ≈ 时间序），用于审计与测试断言。 */
    List<ListingAppeal> findByListingIdOrderByIdAsc(Long listingId);
}

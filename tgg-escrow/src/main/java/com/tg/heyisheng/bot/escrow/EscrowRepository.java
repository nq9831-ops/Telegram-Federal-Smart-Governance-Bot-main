package com.tg.heyisheng.bot.escrow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 担保订单仓库（表 {@code escrow_orders}，模块十二）。
 *
 * <p><b>无物理删除</b>：订单是审计与争议裁决的依据（同模块五/六「软删不物理删」纪律）。
 *
 * <p>只读查询侧（gap-ESC-05：避免重演 gap-07「有写入无查询入口」）由 {@link #findByStateOrderByIdAsc}
 * 等按状态取批的入口提供，争议 / 裁决队列据此列单。
 */
public interface EscrowRepository extends JpaRepository<EscrowOrder, Long> {

    /** 按状态取批（审计 / 争议裁决队列入口）。 */
    List<EscrowOrder> findByStateOrderByIdAsc(String state);

    /** 某买家参与的全部订单（查询侧）。 */
    List<EscrowOrder> findByBuyerUserIdOrderByIdAsc(long buyerUserId);
}

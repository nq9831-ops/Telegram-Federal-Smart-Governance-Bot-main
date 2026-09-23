package com.tg.heyisheng.bot.escrow;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 担保订单仓库（表 {@code escrow_orders}，模块十二）。
 *
 * <p><b>无物理删除</b>：订单是审计与争议裁决的依据（同模块五/六「软删不物理删」纪律）。
 *
 * <p>查询侧分两类：
 * <ul>
 *   <li><b>按主体</b>（买家/卖家）——供 {@code /escrow list} 这类"我的订单"；</li>
 *   <li><b>分页 + 状态筛选</b>——供后台只读面（运营者与联邦裁决方查看全部订单）。
 *       过滤下沉到查询条件（与「我的收录」同一纪律），绝不在控制器里"查全部再过滤"。</li>
 * </ul>
 */
public interface EscrowRepository extends JpaRepository<EscrowOrder, Long> {

    /** 按状态取批（审计 / 争议裁决队列入口）。 */
    List<EscrowOrder> findByStateOrderByIdAsc(String state);

    /** 某买家参与的全部订单（查询侧）。 */
    List<EscrowOrder> findByBuyerUserIdOrderByIdAsc(long buyerUserId);

    /**
     * 某卖家参与的全部订单（查询侧）。
     *
     * <p>V24 已建 {@code idx_escrow_seller} 索引，但此前没有对应方法——即"索引建了、查询侧没接"。
     * {@code /escrow list} 依赖它。
     */
    List<EscrowOrder> findBySellerUserIdOrderByIdAsc(long sellerUserId);

    // ── 后台只读面（分页）────────────────────────────────────────────────────

    /** 全量分页（新在前）——后台订单列表的默认视图。 */
    Page<EscrowOrder> findAllByOrderByIdDesc(Pageable pageable);

    /** 按状态分页（新在前）——争议队列 / 超时单的筛选视图。 */
    Page<EscrowOrder> findByStateOrderByIdDesc(String state, Pageable pageable);
}

package com.tg.heyisheng.bot.listing.merchant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 商家仓库（表 {@code merchants}，模块六）。
 *
 * <p>本仓库<b>不提供物理删除</b>：入驻申请与复核结论是审计对象，任何「商家没了」的诉求
 * 都应由状态（{@code REJECTED}）表达，而不是抹掉行——与模块五「软删不物理删」同一纪律。
 *
 * <p><b>没有 INSERT IGNORE 式的幂等建行</b>：与 {@code listing_groups} 不同，
 * {@code merchants} 没有业务唯一键（一个用户可以有多个商家），重复申请的抑制
 * 由 {@code MerchantService} 依「是否已有在办申请」判定，而非数据库唯一约束。
 */
public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    /**
     * 某用户的全部商家申请（按 id 升序，保证「取最新/取首个在办」的顺序稳定）。
     */
    List<Merchant> findByOwnerUserIdOrderByIdAsc(long ownerUserId);

    /** 按状态取批（复核队列 / 审计入口）。 */
    List<Merchant> findByStatusOrderByIdAsc(String status);
}

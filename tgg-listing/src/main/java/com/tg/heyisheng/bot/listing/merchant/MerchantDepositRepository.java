package com.tg.heyisheng.bot.listing.merchant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 保证金账本仓库（表 {@code merchant_deposits}，模块六）。
 *
 * <p><b>无物理删除</b>：保证金记录是审计与退还判定的依据（同模块五「软删不物理删」纪律）。
 *
 * <p>{@link #findByMerchantId} 依赖 V6 的 {@code uk_deposit_merchant} 唯一键——
 * 一个商家只有一笔保证金，「取该商家的保证金」因此是确定性的单值查询。
 */
public interface MerchantDepositRepository extends JpaRepository<MerchantDeposit, Long> {

    /** 取某商家的保证金（唯一）。 */
    Optional<MerchantDeposit> findByMerchantId(long merchantId);

    /** 按状态取批（审计 / 待结算队列入口）。 */
    List<MerchantDeposit> findByStateOrderByIdAsc(String state);
}

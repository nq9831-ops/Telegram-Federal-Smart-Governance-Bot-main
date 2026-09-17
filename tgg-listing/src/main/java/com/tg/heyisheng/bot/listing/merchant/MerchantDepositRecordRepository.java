package com.tg.heyisheng.bot.listing.merchant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 保证金流水仓库（表 {@code merchant_deposit_records}，模块六）。
 *
 * <p><b>只读入口 + 追加</b>：除 {@code JpaRepository} 自带的写操作外不提供修改/删除语义——
 * 账目流水的正确性依赖「不可变」，任何更正都应以新流水表达。
 */
public interface MerchantDepositRecordRepository extends JpaRepository<MerchantDepositRecord, Long> {

    /** 某笔保证金的时间序流水（按 id 升序 = 发生顺序）。 */
    List<MerchantDepositRecord> findByDepositIdOrderByIdAsc(long depositId);
}

package com.tg.heyisheng.bot.listing.verification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 验证记录仓库（模块五）。
 *
 * <p>记录是<b>只追加</b>的审计流水：不做更新、不做物理删除。
 */
public interface VerificationRecordRepository extends JpaRepository<VerificationRecord, Long> {

    /** 按条目取全部验证记录（时间序近似 = 主键序），用于审计与测试断言。 */
    List<VerificationRecord> findByListingIdOrderByIdAsc(Long listingId);
}

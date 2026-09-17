package com.tg.heyisheng.bot.listing;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 群组收录库仓库。
 *
 * <p>本波（Wave 1）只暴露标准 CRUD；写入幂等（{@code chat_id} 唯一键 + {@code INSERT IGNORE}）
 * 与状态机查询在后续波（Wave 2）补入，届时沿用
 * {@code CreditScoreRepository.insertIfAbsent} 的 native 幂等模式——不用「先查再存 + catch
 * 唯一约束异常」，因为 Hibernate 在 flush 失败后 session 即不可用。
 */
public interface ListingGroupRepository extends JpaRepository<ListingGroup, Long> {
}

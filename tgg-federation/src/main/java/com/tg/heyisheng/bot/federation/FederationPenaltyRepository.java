package com.tg.heyisheng.bot.federation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * 接收的联邦处罚令仓库。
 *
 * <p>幂等由数据库承担：{@code order_id} 唯一约束 + {@code INSERT IGNORE}——
 * <b>不用"先查再存 + catch 唯一约束异常"</b>，因为 Hibernate 在 flush 失败后 session 即不可用
 * （同 {@code BannedWordRepository.insertIgnore} 的教训）。
 *
 * @return 受影响行数：1 = 首次落库；0 = 该 {@code order_id} 已存在（重复投递）
 */
public interface FederationPenaltyRepository extends JpaRepository<FederationPenaltyRecord, Long> {

    Optional<FederationPenaltyRecord> findByOrderId(String orderId);

    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT IGNORE INTO federation_penalties "
            + "(order_id, subject_type, subject_id, penalty_type, issued_at, signature, origin_node, received_at) "
            + "VALUES (:orderId, :subjectType, :subjectId, :penaltyType, :issuedAt, :signature, :originNode, :receivedAt)",
            nativeQuery = true)
    int insertIgnore(@Param("orderId") String orderId,
                     @Param("subjectType") String subjectType,
                     @Param("subjectId") long subjectId,
                     @Param("penaltyType") String penaltyType,
                     @Param("issuedAt") Instant issuedAt,
                     @Param("signature") String signature,
                     @Param("originNode") String originNode,
                     @Param("receivedAt") Instant receivedAt);
}

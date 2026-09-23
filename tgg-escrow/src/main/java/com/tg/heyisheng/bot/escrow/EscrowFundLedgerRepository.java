package com.tg.heyisheng.bot.escrow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 担保交易<b>资金流水</b>仓库（只追加）。
 *
 * <p>写入走 native {@code INSERT IGNORE}（与 {@code CreditEventRecordRepository} 同一模式）——
 * <b>不用 catch 唯一约束异常</b>：Hibernate 在 flush 失败后 session 即不可用，同事务后续操作全崩
 * （本项目实测踩过）。数据库侧的静默跳过才是可组合的幂等。
 *
 * <p><b>刻意不声明任何 delete/update 方法</b>：资金流水是审计与对账依据，只追加。
 * 需要修正时写一条反向流水（新行），而不是改旧行——与 {@code audit_log} 同款纪律。
 */
public interface EscrowFundLedgerRepository extends JpaRepository<EscrowFundLedger, Long> {

    /**
     * 幂等写入一条资金流水。
     *
     * @return 受影响行数：1 = 本次写入；0 = 同键已存在（重复动作，调用方据此跳过后续资金步骤）
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT IGNORE INTO escrow_fund_ledger "
            + "(order_id, direction, amount, currency, subject_user_id, chain_ref, chain_state, "
            + " reason, idempotency_key, occurred_at) "
            + "VALUES (:orderId, :direction, :amount, :currency, :subjectUserId, :chainRef, :chainState, "
            + " :reason, :idempotencyKey, :occurredAt)", nativeQuery = true)
    int insertIfAbsent(@Param("orderId") Long orderId,
                       @Param("direction") String direction,
                       @Param("amount") BigDecimal amount,
                       @Param("currency") String currency,
                       @Param("subjectUserId") Long subjectUserId,
                       @Param("chainRef") String chainRef,
                       @Param("chainState") String chainState,
                       @Param("reason") String reason,
                       @Param("idempotencyKey") String idempotencyKey,
                       @Param("occurredAt") Instant occurredAt);

    /** 某订单的全部资金流水（时间顺序）。 */
    List<EscrowFundLedger> findByOrderIdOrderByIdAsc(Long orderId);

    /** 某用户的全部资金流水（时间顺序）——查询侧入口，供后台/对账使用。 */
    List<EscrowFundLedger> findBySubjectUserIdOrderByIdAsc(long subjectUserId);
}

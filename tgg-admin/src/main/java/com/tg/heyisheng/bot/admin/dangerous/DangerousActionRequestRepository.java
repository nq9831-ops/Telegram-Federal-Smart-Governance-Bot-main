package com.tg.heyisheng.bot.admin.dangerous;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 危险动作复核请求仓库。
 *
 * <p>只声明需要的方法（同 {@code AuditLogRepository} 的取舍）。复核记录**不提供删除**——
 * 它是"谁在何时请求、谁批准"的凭据，删掉等于抹去追责链。
 *
 * <p><b>状态变更是条件更新（{@code ... WHERE status='PENDING'}）</b>：复核的本质是"至多一次"，
 * 而"读出 isPending → 改状态 → 写回"是典型的 check-then-act——并发双击会让两个事务都读到
 * PENDING 并各自触发动作。条件更新把「只有一个能改成 APPROVED」交给数据库的原子性，
 * 受影响行数为 0 即代表"被别人抢先裁决了"。
 */
public interface DangerousActionRequestRepository extends Repository<DangerousActionRequest, Long> {

    DangerousActionRequest save(DangerousActionRequest request);

    Optional<DangerousActionRequest> findById(Long id);

    /** 待批准队列（倒序 = 最新在前）。 */
    List<DangerousActionRequest> findByStatusOrderByIdDesc(DangerousActionRequest.Status status);

    /** 抢占式批准：仅当仍为 PENDING 时置为 APPROVED。返回 1 = 本次抢到；0 = 已被他人裁决。 */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE dangerous_action_requests "
            + "SET status = 'APPROVED', decided_by_type = :decidedByType, decided_by_id = :decidedById, "
            + "    decided_at = :decidedAt, note = :note "
            + "WHERE id = :id AND status = 'PENDING'", nativeQuery = true)
    int approveIfPending(@Param("id") Long id,
                         @Param("decidedByType") String decidedByType,
                         @Param("decidedById") Long decidedById,
                         @Param("note") String note,
                         @Param("decidedAt") Instant decidedAt);

    /** 抢占式拒绝：仅当仍为 PENDING 时置为 REJECTED。返回 1 = 本次抢到；0 = 已被他人裁决。 */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE dangerous_action_requests "
            + "SET status = 'REJECTED', decided_by_type = :decidedByType, decided_by_id = :decidedById, "
            + "    decided_at = :decidedAt, note = :note "
            + "WHERE id = :id AND status = 'PENDING'", nativeQuery = true)
    int rejectIfPending(@Param("id") Long id,
                        @Param("decidedByType") String decidedByType,
                        @Param("decidedById") Long decidedById,
                        @Param("note") String note,
                        @Param("decidedAt") Instant decidedAt);
}

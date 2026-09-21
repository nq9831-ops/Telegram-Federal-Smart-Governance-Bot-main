package com.tg.heyisheng.bot.admin.dangerous;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 危险动作复核请求仓库。
 *
 * <p>只声明需要的方法（同 {@code AuditLogRepository} 的取舍）。复核记录**不提供删除**——
 * 它是"谁在何时请求、谁批准"的凭据，删掉等于抹去追责链。
 */
public interface DangerousActionRequestRepository extends Repository<DangerousActionRequest, Long> {

    DangerousActionRequest save(DangerousActionRequest request);

    Optional<DangerousActionRequest> findById(Long id);

    /** 待批准队列（倒序 = 最新在前）。 */
    List<DangerousActionRequest> findByStatusOrderByIdDesc(DangerousActionRequest.Status status);
}

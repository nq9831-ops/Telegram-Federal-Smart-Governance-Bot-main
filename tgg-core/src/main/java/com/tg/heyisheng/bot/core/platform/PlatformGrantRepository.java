package com.tg.heyisheng.bot.core.platform;

import com.tg.heyisheng.bot.core.audit.ActorType;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 平台能力授权仓库。
 *
 * <p>只声明需要的方法（同 {@code AuditLogRepository} 的取舍）。与审计不同，本表<b>需要撤销</b>
 * （超管收回操作员的能力），故显式声明删除方法——删除是它的正常业务操作，不是「留了口子」。
 */
public interface PlatformGrantRepository extends Repository<PlatformGrant, Long> {

    PlatformGrant save(PlatformGrant grant);

    Optional<PlatformGrant> findBySubjectTypeAndSubjectIdAndPermission(
            ActorType subjectType, Long subjectId, PlatformPermission permission);

    List<PlatformGrant> findBySubjectTypeAndSubjectId(ActorType subjectType, Long subjectId);

    /** 全部授权（超管列出账本用），顺序稳定。 */
    List<PlatformGrant> findAllByOrderByIdAsc();

    void deleteBySubjectTypeAndSubjectIdAndPermission(
            ActorType subjectType, Long subjectId, PlatformPermission permission);
}

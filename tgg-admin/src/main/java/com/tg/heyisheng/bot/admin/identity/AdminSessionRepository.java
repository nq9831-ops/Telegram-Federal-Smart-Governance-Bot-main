package com.tg.heyisheng.bot.admin.identity;

import com.tg.heyisheng.bot.core.audit.ActorType;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 后台会话仓库。
 *
 * <p>同样只声明需要的方法（见 {@link AdminAccountRepository} 的取舍说明）。
 */
public interface AdminSessionRepository extends Repository<AdminSession, Long> {

    AdminSession save(AdminSession session);

    /** 按令牌哈希查（校验会话用；明文令牌不入库，故只能按哈希查）。 */
    Optional<AdminSession> findByTokenHash(String tokenHash);

    /** 某主体的全部会话（强制下线 / 停用时批量吊销用）。 */
    List<AdminSession> findBySubjectTypeAndSubjectId(ActorType subjectType, Long subjectId);
}

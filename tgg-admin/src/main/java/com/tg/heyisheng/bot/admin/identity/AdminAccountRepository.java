package com.tg.heyisheng.bot.admin.identity;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 后台账号仓库。
 *
 * <p><b>刻意继承空标记接口 {@link Repository} 而非 {@code JpaRepository}</b>（同 {@code AuditLogRepository} 的取舍）：
 * 只声明真正需要的方法。特别地，<b>本接口刻意不暴露 delete</b>——账号删除会留下悬空的审计引用，
 * 且「误删超管」不可逆；停用（{@code status=DISABLED}）已能表达「这个账号不再可用」。
 * 若将来确需删除，应在服务层显式说明并新增方法，而不是顺手继承一个 deleteById。
 */
public interface AdminAccountRepository extends Repository<AdminAccount, Long> {

    /** 新增或更新。 */
    AdminAccount save(AdminAccount account);

    Optional<AdminAccount> findById(Long id);

    /** 按登录名查（登录用）。 */
    Optional<AdminAccount> findByUsername(String username);

    /** 全部账号（超管列出操作员用），顺序稳定（按 id 升序）。 */
    List<AdminAccount> findAllByOrderByIdAsc();

    /** 账号数（引导判定「是否已有超管」用）。 */
    long count();

    boolean existsByUsername(String username);
}

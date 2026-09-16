package com.tg.heyisheng.bot.core.permission;

/**
 * 角色来源——权限判定的数据入口。
 *
 * <p>刻意做成接口：本切片只提供内存实现（见 {@link InMemoryRoleSource}），
 * 而真正的持久化归属是**模块三（群组管理）**的群组管理员配置与
 * **模块十一（Web 后台）**的后台账号体系。届时替换实现即可，判定逻辑不变。
 *
 * <p><b>未知即最小权限</b>：查不到的用户一律按 {@link Role#MEMBER} 处理，
 * 避免"查不到就放行"这类危险默认。
 */
public interface RoleSource {

    /**
     * @param chatId 群组 ID
     * @param userId 用户 ID
     * @return 该用户在该群的角色；无记录时返回 {@link Role#MEMBER}
     */
    Role roleOf(Long chatId, Long userId);
}

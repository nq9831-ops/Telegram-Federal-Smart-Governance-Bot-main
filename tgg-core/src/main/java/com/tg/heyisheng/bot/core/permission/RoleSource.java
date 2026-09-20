package com.tg.heyisheng.bot.core.permission;

import java.util.List;

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

    /**
     * 已授予的角色清单（供需要**枚举**授权项的场景使用）。
     *
     * <p>目前唯一的消费方是「按授权分层注册客户端命令菜单」：它需要知道该给哪些
     * {@code (chatId, userId)} 注册管理命令，而 {@link #roleOf} 只能逐点查询、无法反查。
     *
     * <p><b>实现纪律</b>：返回顺序必须**稳定**（建议按 chatId、userId 升序），
     * 否则注册日志与依赖顺序的断言会随机抖动。无记录时返回空列表。
     */
    List<RoleGrant> grants();
}

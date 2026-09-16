package com.tg.heyisheng.bot.core.permission;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存角色源——切片 3 的实现，供开发与测试使用。
 *
 * <p><b>性质说明（与装配事实保持一致）</b>：本类**当前就是生产装配的实现**——
 * 由 {@code TggCoreConfiguration#roleSource} 装配，授权从配置项
 * {@code tgg.permission.admins} 在启动时载入。因此它是「配置即持久化载体」：
 * 重启后授权从配置重放，不会丢失。
 *
 * <p><b>局限（明确记录）</b>：进程内存储、多实例之间不同步；
 * 运行期变更授权需重启或另加管理接口。
 * 模块三/十一引入数据库后应替换为持久化实现——届时判定逻辑不变，只换数据来源。
 */
public class InMemoryRoleSource implements RoleSource {

    private final Map<Long, Map<Long, Role>> rolesByChat = new ConcurrentHashMap<>();

    /** 授予/变更某用户在某群的角色。 */
    public void assign(Long chatId, Long userId, Role role) {
        if (chatId == null || userId == null || role == null) {
            throw new IllegalArgumentException("chatId / userId / role 均不可为空");
        }
        rolesByChat.computeIfAbsent(chatId, k -> new ConcurrentHashMap<>()).put(userId, role);
    }

    /** 撤销某用户在某群的角色（回到 MEMBER）。 */
    public void revoke(Long chatId, Long userId) {
        Map<Long, Role> inChat = rolesByChat.get(chatId);
        if (inChat != null) {
            inChat.remove(userId);
        }
    }

    @Override
    public Role roleOf(Long chatId, Long userId) {
        if (chatId == null || userId == null) {
            return Role.MEMBER;
        }
        Map<Long, Role> inChat = rolesByChat.get(chatId);
        if (inChat == null) {
            return Role.MEMBER;
        }
        return inChat.getOrDefault(userId, Role.MEMBER);
    }
}

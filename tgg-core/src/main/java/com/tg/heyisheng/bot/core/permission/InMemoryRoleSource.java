package com.tg.heyisheng.bot.core.permission;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存角色源——切片 3 的实现，供开发与测试使用。
 *
 * <p><b>性质说明</b>：进程内存储，重启即失；不跨实例共享。
 * 这对本阶段的 Bot 是够用的（单实例、权限配置量小），
 * 但**不可用于生产**——生产需换成模块三/十一提供的持久化实现。
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

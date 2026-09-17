package com.tg.heyisheng.bot.federation;

import java.util.Set;

/**
 * 联邦管理员判定（模块八）：**全局 userId 白名单**。
 *
 * <p><b>为什么不复用群内 RBAC</b>：现有 {@code Role} / {@code RoleGrantParser} 是**群内**语义
 * （格式 {@code <chatId>:<userId>[:role]}），而联邦管理员是**跨群/全局**角色——
 * 他管理的是联邦，不属于某个群。硬塞进群内模型会让授权源语义错配，
 * 故用独立白名单（配置 {@code tgg.federation.admins}）。
 *
 * <p>门控仍在命令层（handler 内判定），只是授权源不同。
 */
public class FederationAdminGuard {

    private final Set<Long> adminIds;

    public FederationAdminGuard(Iterable<Long> adminIds) {
        Set<Long> set = new java.util.HashSet<>();
        if (adminIds != null) {
            for (Long id : adminIds) {
                if (id != null) {
                    set.add(id);
                }
            }
        }
        this.adminIds = Set.copyOf(set);
    }

    /** 该用户是否为联邦管理员。 */
    public boolean isAdmin(Long userId) {
        return userId != null && adminIds.contains(userId);
    }

    /** 已配置的联邦管理员数量（供装配期告警与测试使用）。 */
    public int size() {
        return adminIds.size();
    }
}

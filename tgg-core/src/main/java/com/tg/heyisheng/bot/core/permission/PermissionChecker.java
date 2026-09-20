package com.tg.heyisheng.bot.core.permission;

import java.util.List;

/**
 * 权限判定：把「角色」与「权限点」合成一个可用/不可用的结论。
 *
 * <p>判定链：{@code (chatId, userId) → RoleSource → Role → has(Permission)}。
 */
public class PermissionChecker {

    private final RoleSource roleSource;

    public PermissionChecker(RoleSource roleSource) {
        this.roleSource = roleSource;
    }

    /**
     * 「全员同角色」的判定器——授权源是一条**常量函数**：对任何 {@code (chatId, userId)}
     * 都返回同一个角色，且没有任何授权项。
     *
     * <p><b>两个正当用途</b>：① {@code CommandDispatcher} 的「全部按最小权限处理」默认构造器
     * （{@link Role#MEMBER}，未装配权限源时不放行任何受限命令）；② 测试里「全员同权 / 全员无权」。
     * 生产中给真实用户授权请走配置驱动的那条路（{@code TggCoreConfiguration#roleSource}
     * 读 {@code tgg.permission.admins}）——别用本构造器当生产授权源。
     *
     * <p>与 {@code TeachEligibility.allowAll()} 同一取舍：给最常见的常量场景一个具名入口，
     * 免得每个调用方各写一个匿名类（复制粘贴迟早漂移）。
     */
    public PermissionChecker(Role fixedRole) {
        this(fixedSource(fixedRole));
    }

    private static RoleSource fixedSource(Role role) {
        if (role == null) {
            throw new IllegalArgumentException("角色不可为空——未知即最小权限请显式传 Role.MEMBER");
        }
        return new RoleSource() {
            @Override
            public Role roleOf(Long chatId, Long userId) {
                return role;
            }

            @Override
            public List<RoleGrant> grants() {
                // 常量函数没有「授权项」可言——按 (chat, user) 列出来的东西不存在
                return List.of();
            }
        };
    }

    /**
     * @param permission 所需权限；{@code null} 或 {@link Permission#NONE} 表示不设门槛
     * @return 是否放行
     */
    public boolean has(Long chatId, Long userId, Permission permission) {
        if (permission == null || permission == Permission.NONE) {
            return true;
        }
        return roleSource.roleOf(chatId, userId).has(permission);
    }

    /** 便于调用方给出「你是谁」的提示。 */
    public Role roleOf(Long chatId, Long userId) {
        return roleSource.roleOf(chatId, userId);
    }
}

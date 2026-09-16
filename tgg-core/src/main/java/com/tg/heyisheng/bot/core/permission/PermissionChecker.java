package com.tg.heyisheng.bot.core.permission;

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

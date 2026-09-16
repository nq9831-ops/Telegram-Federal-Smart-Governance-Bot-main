package com.tg.heyisheng.bot.core.permission;

import java.util.Set;

/**
 * 群组内的角色层级。
 *
 * <p>V5.0 的治理哲学是「群主管群、联邦管底线」——角色只描述**群内**权能，
 * 不涉及联邦层面（那属于模块八）。
 *
 * <p>当前 {@code OWNER} 与 {@code ADMIN} 权限集相同：V5.0 未细分二者差异，
 * 但保留层级以便未来加入「仅群主可做」的操作（如群组转让）。
 */
public enum Role {

    /** 群主。 */
    OWNER(Set.of(Permission.BAN_USER, Permission.MANAGE_CONFIG, Permission.TEACH_RULE)),

    /** 群管理员。 */
    ADMIN(Set.of(Permission.BAN_USER, Permission.MANAGE_CONFIG, Permission.TEACH_RULE)),

    /** 版主：只能做处置类操作，不能改配置或教学。 */
    MODERATOR(Set.of(Permission.BAN_USER)),

    /** 普通成员。 */
    MEMBER(Set.of());

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    /**
     * @param permission 待判定的权限；{@link Permission#NONE} 恒为真
     * @return 本角色是否具备该权限
     */
    public boolean has(Permission permission) {
        return permission == Permission.NONE || permissions.contains(permission);
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}

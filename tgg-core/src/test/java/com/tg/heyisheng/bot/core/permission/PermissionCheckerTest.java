package com.tg.heyisheng.bot.core.permission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 权限判定测试。
 *
 * <p>重点覆盖两个安全属性：**未知即最小权限**、**角色按群隔离**。
 */
class PermissionCheckerTest {

    private static final long CHAT_A = -100L;
    private static final long CHAT_B = -200L;
    private static final long USER = 7L;

    private final InMemoryRoleSource roleSource = new InMemoryRoleSource();
    private final PermissionChecker checker = new PermissionChecker(roleSource);

    @Test
    void nonePermissionIsAlwaysAllowed() {
        assertThat(checker.has(CHAT_A, USER, Permission.NONE)).isTrue();
        assertThat(checker.has(CHAT_A, USER, null)).isTrue();
    }

    @Test
    void unknownUserIsMemberWithoutPermissions() {
        assertThat(checker.roleOf(CHAT_A, USER)).isEqualTo(Role.MEMBER);
        assertThat(checker.has(CHAT_A, USER, Permission.BAN_USER)).isFalse();
    }

    @Test
    void adminCanBanAndManageConfig() {
        roleSource.assign(CHAT_A, USER, Role.ADMIN);

        assertThat(checker.has(CHAT_A, USER, Permission.BAN_USER)).isTrue();
        assertThat(checker.has(CHAT_A, USER, Permission.MANAGE_CONFIG)).isTrue();
        assertThat(checker.has(CHAT_A, USER, Permission.TEACH_RULE)).isTrue();
    }

    @Test
    void moderatorCanBanButNotChangeConfig() {
        roleSource.assign(CHAT_A, USER, Role.MODERATOR);

        assertThat(checker.has(CHAT_A, USER, Permission.BAN_USER)).isTrue();
        assertThat(checker.has(CHAT_A, USER, Permission.MANAGE_CONFIG))
                .as("版主不应能改群组配置").isFalse();
        assertThat(checker.has(CHAT_A, USER, Permission.TEACH_RULE))
                .as("版主不应能提交规则").isFalse();
    }

    /** 角色必须按群隔离——A 群的管理员在 B 群什么都不是。 */
    @Test
    void roleIsScopedToChat() {
        roleSource.assign(CHAT_A, USER, Role.ADMIN);

        assertThat(checker.has(CHAT_A, USER, Permission.BAN_USER)).isTrue();
        assertThat(checker.has(CHAT_B, USER, Permission.BAN_USER))
                .as("群 A 的管理员在群 B 不得有权限").isFalse();
    }

    @Test
    void revokedRoleFallsBackToMember() {
        roleSource.assign(CHAT_A, USER, Role.ADMIN);
        roleSource.revoke(CHAT_A, USER);

        assertThat(checker.roleOf(CHAT_A, USER)).isEqualTo(Role.MEMBER);
        assertThat(checker.has(CHAT_A, USER, Permission.BAN_USER)).isFalse();
    }

    @Test
    void nullIdentifiersAreTreatedAsMember() {
        assertThat(checker.roleOf(null, USER)).isEqualTo(Role.MEMBER);
        assertThat(checker.roleOf(CHAT_A, null)).isEqualTo(Role.MEMBER);
    }

    @Test
    void assignRejectsNullArguments() {
        assertThatThrownBy(() -> roleSource.assign(null, USER, Role.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

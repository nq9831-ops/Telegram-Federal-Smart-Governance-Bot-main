package com.tg.heyisheng.bot.core.permission;

import com.tg.heyisheng.bot.common.exception.TggConfigException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产授权入口的解析测试。
 *
 * <p>存在意义：切片 3b 交付时没有任何授予角色的通道，导致权限门控在生产中不可达。
 * 本类保证「改配置即可授权」这条路径可用，且配置错误在启动期就暴露。
 */
class RoleGrantParserTest {

    private final InMemoryRoleSource source = new InMemoryRoleSource();

    @Test
    void parsesChatUserAndRole() {
        RoleGrantParser.apply(source, "-100:42:MODERATOR");

        assertThat(source.roleOf(-100L, 42L)).isEqualTo(Role.MODERATOR);
    }

    @Test
    void defaultsToAdminWhenRoleOmitted() {
        RoleGrantParser.apply(source, "-100:42");

        assertThat(source.roleOf(-100L, 42L)).isEqualTo(Role.ADMIN);
    }

    @Test
    void parsesMultipleEntriesIgnoringWhitespace() {
        RoleGrantParser.apply(source, " -100:1:ADMIN , -200:2 ");

        assertThat(source.roleOf(-100L, 1L)).isEqualTo(Role.ADMIN);
        assertThat(source.roleOf(-200L, 2L)).isEqualTo(Role.ADMIN);
    }

    @Test
    void emptySpecGrantsNothing() {
        RoleGrantParser.apply(source, "");
        RoleGrantParser.apply(source, null);

        assertThat(source.roleOf(-100L, 1L)).isEqualTo(Role.MEMBER);
    }

    /** 配置写错必须在启动期炸掉——静默忽略会让"以为授权了"变成长期隐患。 */
    @Test
    void rejectsEntryMissingUserId() {
        assertThatThrownBy(() -> RoleGrantParser.apply(source, "-100"))
                .isInstanceOf(TggConfigException.class)
                .hasMessageContaining("chatId");
    }

    @Test
    void rejectsNonNumericId() {
        assertThatThrownBy(() -> RoleGrantParser.apply(source, "-100:abc"))
                .isInstanceOf(TggConfigException.class)
                .hasMessageContaining("不是合法数字");
    }

    @Test
    void rejectsUnknownRole() {
        assertThatThrownBy(() -> RoleGrantParser.apply(source, "-100:1:SUPERUSER"))
                .isInstanceOf(TggConfigException.class)
                .hasMessageContaining("无法识别的角色");
    }

    /**
     * 回归（安全）：漏填角色名必须报错，不得静默授予 ADMIN。
     *
     * <p>Java 的 {@code split(":")} 会丢弃尾部空串，使 {@code "-100:42:"} 与
     * {@code "-100:42"}（有意省略角色段）都解析成 2 段——若不区分，
     * 一个手滑的冒号就会把普通用户提权为管理员。
     */
    @Test
    void rejectsTrailingColonWithoutRoleName() {
        assertThatThrownBy(() -> RoleGrantParser.apply(source, "-100:42:"))
                .isInstanceOf(TggConfigException.class)
                .hasMessageContaining("未填角色名");
    }

    @Test
    void omittingRoleEntirelyStillDefaultsToAdmin() {
        RoleGrantParser.apply(source, "-100:42");

        assertThat(source.roleOf(-100L, 42L)).isEqualTo(Role.ADMIN);
    }

    @Test
    void roleNameIsCaseInsensitive() {
        RoleGrantParser.apply(source, "-100:9:moderator");

        assertThat(source.roleOf(-100L, 9L)).isEqualTo(Role.MODERATOR);
    }
}

package com.tg.heyisheng.bot.core.permission;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RoleSource#grants()} —— 授权项的**枚举**能力。
 *
 * <p>它是「按授权分层注册客户端命令菜单」的数据源：客户端菜单要知道该给哪些
 * {@code (chatId, userId)} 注册管理命令，而 {@link RoleSource#roleOf} 只能逐点查询、无法反查。
 *
 * <p>两条不变量：① 顺序**稳定**（内部是 HashMap，不排序会让注册日志与断言随机抖动）；
 * ② 返回**快照**（此后 assign/revoke 不得改变已返回的列表）。
 */
class RoleSourceGrantsTest {

    @Test
    void emptySourceHasNoGrants() {
        assertThat(new InMemoryRoleSource().grants()).isEmpty();
    }

    /** 顺序按 (chatId, userId) 升序——与插入顺序无关。 */
    @Test
    void grantsAreOrderedByChatThenUserRegardlessOfInsertionOrder() {
        InMemoryRoleSource source = new InMemoryRoleSource();
        source.assign(-100L, 42L, Role.ADMIN);
        source.assign(-100L, 7L, Role.MODERATOR);
        source.assign(-200L, 42L, Role.OWNER);

        assertThat(source.grants()).containsExactly(
                new RoleGrant(-200L, 42L, Role.OWNER),
                new RoleGrant(-100L, 7L, Role.MODERATOR),
                new RoleGrant(-100L, 42L, Role.ADMIN));
    }

    @Test
    void revokeRemovesTheGrant() {
        InMemoryRoleSource source = new InMemoryRoleSource();
        source.assign(-100L, 42L, Role.ADMIN);
        source.assign(-100L, 7L, Role.MODERATOR);

        source.revoke(-100L, 42L);

        assertThat(source.grants()).containsExactly(new RoleGrant(-100L, 7L, Role.MODERATOR));
    }

    /** 返回的是快照：拿到之后本对象的变更不得改变它（否则调用方会读到「正在变」的集合）。 */
    @Test
    void grantsIsASnapshotNotALiveView() {
        InMemoryRoleSource source = new InMemoryRoleSource();
        source.assign(-100L, 42L, Role.ADMIN);

        List<RoleGrant> before = source.grants();
        source.assign(-100L, 7L, Role.ADMIN);
        source.assign(-300L, 9L, Role.OWNER);

        assertThat(before).containsExactly(new RoleGrant(-100L, 42L, Role.ADMIN));
        assertThat(source.grants()).hasSize(3);
    }

    /** 配置解析出来的授权同样可枚举——生产路径（{@code tgg.permission.admins}）走的就是这条。 */
    @Test
    void parserFilledGrantsAreEnumerable() {
        InMemoryRoleSource source = new InMemoryRoleSource();

        RoleGrantParser.apply(source, " -100:1:ADMIN , -200:2 ");

        assertThat(source.grants()).containsExactly(
                new RoleGrant(-200L, 2L, Role.ADMIN),
                new RoleGrant(-100L, 1L, Role.ADMIN));
    }
}

package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.core.platform.FederationAdminGuard;

import com.tg.heyisheng.bot.core.interaction.MenuVisibility;

import java.util.Set;

/**
 * 「复核合规 / 联邦」部分的可见性接缝（模块八）：联邦申诉裁决类命令的权限载体是**全局**白名单
 * （{@link FederationAdminGuard}，配置 {@code tgg.federation.admins}），注解权限是 {@code NONE}
 * ——{@code /menu} 从注册表判不出「此人是否可见」。
 *
 * <p><b>为什么不复用群内 RBAC</b>（照模块六 / 模块九同款取舍）：联邦管理员管理的是联邦，
 * 不属于任何一个群；硬塞进 {@code <chatId>:<userId>} 的群内模型会让授权源语义错配。
 * 故判定留在本模块，core 只定义接缝并负责聚合。
 *
 * <p><b>{@code /appeal} 刻意不在本接缝内</b>：它是**成员自助**（提交申诉），不是管理动作
 * ——进 /menu 会让「管理面板」语义失真。它照旧走客户端命令列表。
 *
 * <p><b>判定与执行同源</b>：这里调 {@code isAdmin}，与各 handler 内用的是同一个
 * {@link FederationAdminGuard}。两处若各写一份条件，迟早漂移成「菜单看得见、点了不生效」
 * 或（更糟）对无权者暴露命令存在。
 */
public class FederationMenuVisibility implements MenuVisibility {

    /** 本接缝负责的命令——与调 {@code FederationAdminGuard.isAdmin} 的 handler 一一对应。 */
    static final Set<String> COMMANDS = Set.of("approve", "reject", "pending");

    private final FederationAdminGuard guard;

    public FederationMenuVisibility(FederationAdminGuard guard) {
        this.guard = guard;
    }

    @Override
    public Set<String> commands() {
        return COMMANDS;
    }

    @Override
    public boolean visible(long chatId, Long userId) {
        // 白名单是全局的，与群无关——chatId 不参与判定（保留形参是为了接口统一）
        return guard.isAdmin(userId);
    }
}

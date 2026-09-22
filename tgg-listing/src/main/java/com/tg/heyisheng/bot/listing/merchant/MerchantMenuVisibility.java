package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.core.platform.FederationAdminGuard;

import com.tg.heyisheng.bot.core.interaction.MenuVisibility;

import java.util.Set;

/**
 * 「商家收录」类的可见性接缝（模块六）：资质复核类命令的权限载体是**平台层**全局白名单
 * （{@link FederationAdminGuard}，配置 {@code tgg.federation.admins}——商家只有联邦管理员才可以审核处理），
 * 注解权限是 {@code NONE}——{@code /menu} 从注册表判不出「此人是否可见」。
 *
 * <p><b>与模块九 / 模块八同款取舍</b>：{@code Permission}/{@code Role} 是群内权能模型，
 * 装不下跨群的平台角色（「资质复核人」不该因某个群的管理员配置变化而获得或失去权能）。
 * 故判定留在本模块，core 只定义接缝 {@link MenuVisibility} 并负责聚合。
 *
 * <p><b>判定与执行同源</b>：这里调 {@code isReviewer}，与各 handler 内用的是同一个
 * {@link FederationAdminGuard}——两处若各写一份条件，迟早漂移成「菜单看得见、点了不生效」
 * 或（更糟）对无权者暴露命令存在。
 *
 * <p><b>本接缝由 {@code MerchantConfiguration} 装配</b>，因此随 {@code tgg.merchant.enabled}
 * 一同出现/消失：模块未启用时不得存在「商家收录」分类（那些命令本就不在容器里）。
 */
public class MerchantMenuVisibility implements MenuVisibility {

    /** 本接缝负责的命令——与调 {@code FederationAdminGuard.isReviewer} 的 handler 一一对应。 */
    static final Set<String> COMMANDS =
            Set.of("merchant_review", "merchant_deposit", "merchant_settle");

    private final FederationAdminGuard guard;

    public MerchantMenuVisibility(FederationAdminGuard guard) {
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

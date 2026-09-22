package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.platform.FederationAdminGuard;

/**
 * 商家域管理身份的**单一事实来源**——{@link MerchantMenuVisibility}（「管理商家」三件的可见性）
 * 与 {@link MerchantSubmissionCuration}（商家入驻自助链的策展）共用它，兑现
 * {@code MenuVisibility} / {@code MenuCurator} javadoc 的「判定必须与该功能的管理判定<b>同源</b>」纪律：
 * 两处若各写一份判据，迟早漂移成「菜单看得见、点了不生效」或（更糟）对无权者暴露命令存在；
 * 策展侧漂移则是「已经不管事了却还被收走自助链」。
 *
 * <p><b>商家域有两个管理档位</b>（权限排名：超级管理员 &gt; 联邦管理员 &gt; 群管理员 &gt; 普通用户）：
 * <ul>
 *   <li>{@link #isFederationAdmin}——联邦管理员（{@link FederationAdminGuard#isAdmin}）。
 *       「管理商家」三件（{@code /merchant_review}·{@code /merchant_deposit}·{@code /merchant_settle}，
 *       含保证金结算这类<b>资金动作</b>）的**执行授权**即此，故其可见性只认这一档；</li>
 *   <li>{@link #isMerchantDomainManager}——上面这档**并上**群内管理员
 *       （{@link PermissionChecker} 判 {@link Permission#MANAGE_CONFIG}）。群管**不并权**
 *       （看不到管理三件，刻意 fail-closed），但仍是「管事的人」（用户点名「管理员不需要提交商家收录」），
 *       其自助链同样被收走。</li>
 * </ul>
 *
 * <p><b>两档为何不合并为一个布尔</b>：它们是两个不同的问题——「能不能执行资金动作」（授权，只认联邦管理员）
 * 与「是不是管事的人」（策展，联邦 ∨ 群管）。既有的
 * {@code RoleMatrixContentTest} / {@code MerchantCurationContentTest} 把这条边界钉死
 * （纯群管：管理三件不见 <b>且</b> 自助链不见 = 空面）。把两个问题分开、却从同一个类取原料，
 * 才是既能对齐来源、又不越过既定权限分叉的做法。
 */
final class MerchantDomainAuthority {

    private final FederationAdminGuard federationGate;
    /** 可空：装配切片上下文可能不含 core 的权限判定器（缺失时群管半边判不了即不收，fail-open）。 */
    private final PermissionChecker permissionChecker;

    MerchantDomainAuthority(FederationAdminGuard federationGate, PermissionChecker permissionChecker) {
        this.federationGate = federationGate;
        this.permissionChecker = permissionChecker;
    }

    /** 联邦管理员——「管理商家」三件的执行授权（{@link MerchantMenuVisibility} 只认这一档）。 */
    boolean isFederationAdmin(Long userId) {
        return federationGate.isAdmin(userId);
    }

    /**
     * 商家域管理者 = 联邦管理员 ∨ 群内管理员（自助链策展的判据）。
     *
     * <p><b>fail-open</b>：身份不明（{@code userId == null}）不收——策展不是安全门
     * （见 {@code MenuCurator}）。权限判定器缺席时群管半边判不了即不收，联邦半边照常。
     */
    boolean isMerchantDomainManager(long chatId, Long userId) {
        if (userId == null) {
            return false;
        }
        return isFederationAdmin(userId)
                || (permissionChecker != null
                        && permissionChecker.has(chatId, userId, Permission.MANAGE_CONFIG));
    }
}

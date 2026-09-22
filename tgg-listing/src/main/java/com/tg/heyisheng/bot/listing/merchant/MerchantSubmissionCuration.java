package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.core.platform.FederationAdminGuard;

import com.tg.heyisheng.bot.core.interaction.MenuCurator;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;

import java.util.Set;

/**
 * 「商家收录」面的策展（模块六，用户 2026-09-22 拍板）：对**管事的人**收走商家入驻自助链——
 * 他们管商家（资质复核 / 保证金），不申请入驻；两类混排正是用户说的「功能模糊」。
 * 管理侧的商家面因此**不再有「提交」噪音**。
 *
 * <p><b>「管理商家」三件归联邦管理员独占</b>（{@code /merchant_review}·{@code /merchant_deposit}·
 * {@code /merchant_settle}，见 {@link MerchantMenuVisibility}）：纯群管**不并权**，其商家面为**空面**
 * （分类整块不出现）——刻意的 fail-closed（保证金结算是资金动作），不是遗漏；要管商家，
 * 成为联邦管理员（{@code TGG_FEDERATION_ADMINS}）。策展边界判据见 {@code RoleMatrixContentTest}。
 *
 * <p><b>「管事的人」的判据</b>（与该功能的管理判定同源）：
 * <ul>
 *   <li>联邦管理员（{@link FederationAdminGuard#isAdmin}——2026-09-22 二次拍板：「商家只有联邦管理员
 *       才可以审核处理」，商家域的管理者即他）；</li>
 *   <li>当前群的群内管理员（{@link PermissionChecker} 判 {@link Permission#MANAGE_CONFIG}，
 *       用户点名「管理员不需要提交商家收录」）。</li>
 * </ul>
 *
 * <p><b>只收展示，不动执行</b>：三条自助命令仍可直接键入执行（用户原话「不需要」而非「不存在」）。
 * <b>fail-open</b>：身份不明（{@code userId == null}）不收——策展不是安全门（见 {@link MenuCurator}）。
 */
public class MerchantSubmissionCuration implements MenuCurator {

    /** 「提交商家收录」的自助链——与三条 {@code /merchant_*} 自助命令 handler 一一对应。 */
    static final Set<String> COMMANDS = Set.of("merchant_apply", "merchant_status", "merchant_exit");

    private final FederationAdminGuard federationGate;
    /** 可空：装配切片上下文可能不含 core 的判定器（缺失时 RBAC 半边判不了即不收，fail-open）。 */
    private final PermissionChecker permissionChecker;

    public MerchantSubmissionCuration(FederationAdminGuard federationGate, PermissionChecker permissionChecker) {
        this.federationGate = federationGate;
        this.permissionChecker = permissionChecker;
    }

    @Override
    public Set<String> commands() {
        return COMMANDS;
    }

    @Override
    public boolean hides(long chatId, Long userId) {
        if (userId == null) {
            return false;
        }
        return federationGate.isAdmin(userId)
                || (permissionChecker != null
                        && permissionChecker.has(chatId, userId, Permission.MANAGE_CONFIG));
    }
}

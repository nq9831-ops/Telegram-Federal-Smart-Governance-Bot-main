package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.core.interaction.MenuCurator;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;

import java.util.Set;

/**
 * 「商家收录」面的策展（模块六，用户 2026-09-22 拍板）：对**管事的人**收走商家入驻自助链——
 * 他们管商家（资质复核 / 保证金），不申请入驻；两类混排正是用户说的「功能模糊」。
 * 管理侧的商家面因此**不再有「提交」噪音**。
 *
 * <p><b>「管理商家」三件另按商家复核授权</b>（{@code /merchant_review}·{@code /merchant_deposit}·
 * {@code /merchant_settle}，见 {@link MerchantMenuVisibility}——四条授权通道**互相独立**，不类比、
 * 不继承）：纯群管未授商家复核权时，其商家面为**空面**（分类整块不出现）。这是刻意的 fail-closed
 * （保证金结算是资金动作，默认不因「是群管」而并权），不是遗漏——群管要管商家，加一行
 * {@code TGG_MERCHANT_REVIEWERS} 即可。策展边界判据见 {@code RoleMatrixContentTest}。
 *
 * <p><b>「管事的人」的判据</b>（与该功能的管理判定同源）：
 * <ul>
 *   <li>商家复核人（{@link MerchantReviewGuard#isReviewer}——全局白名单 / 平台账本）；</li>
 *   <li>当前群的群内管理员（{@link PermissionChecker} 判 {@link Permission#MANAGE_CONFIG}）。</li>
 * </ul>
 *
 * <p><b>只收展示，不动执行</b>：三条自助命令仍可直接键入执行（用户原话「不需要」而非「不存在」）。
 * <b>fail-open</b>：身份不明（{@code userId == null}）不收——策展不是安全门（见 {@link MenuCurator}）。
 */
public class MerchantSubmissionCuration implements MenuCurator {

    /** 「提交商家收录」的自助链——与三条 {@code /merchant_*} 自助命令 handler 一一对应。 */
    static final Set<String> COMMANDS = Set.of("merchant_apply", "merchant_status", "merchant_exit");

    private final MerchantReviewGuard reviewGuard;
    /** 可空：装配切片上下文可能不含 core 的判定器（缺失时 RBAC 半边判不了即不收，fail-open）。 */
    private final PermissionChecker permissionChecker;

    public MerchantSubmissionCuration(MerchantReviewGuard reviewGuard, PermissionChecker permissionChecker) {
        this.reviewGuard = reviewGuard;
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
        return reviewGuard.isReviewer(userId)
                || (permissionChecker != null
                        && permissionChecker.has(chatId, userId, Permission.MANAGE_CONFIG));
    }
}

package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「商家收录」策展的判据（用户 2026-09-22 拍板）：管事的人（商家复核人 / 群内管理员）
 * 的面上收走商家入驻自助链，普通成员与身份不明者照常。
 *
 * <p>判据与管理判定同源：复核侧走 {@link MerchantReviewGuard#isReviewer}（本测用静态名单构造器），
 * 群管侧走 {@link PermissionChecker} 的 {@code MANAGE_CONFIG}（本测用固定角色构造器）。
 */
class MerchantSubmissionCurationTest {

    private static final long CHAT = -777003L;

    /** 命令集合是同步钉：与三条自助命令 handler 一一对应，增删时本断言变红提醒显式核对。 */
    @Test
    void claimsExactlyTheSubmissionFamily() {
        MerchantSubmissionCuration curation = new MerchantSubmissionCuration(
                new MerchantReviewGuard(List.of()), new PermissionChecker(Role.MEMBER));

        assertThat(curation.commands())
                .containsExactlyInAnyOrder("merchant_apply", "merchant_status", "merchant_exit");
    }

    @Test
    void hidesFromMerchantReviewers() {
        MerchantSubmissionCuration curation = new MerchantSubmissionCuration(
                new MerchantReviewGuard(List.of(777001L)), new PermissionChecker(Role.MEMBER));

        assertThat(curation.hides(CHAT, 777001L))
                .as("复核人是「管事的人」——收走自助链")
                .isTrue();
        assertThat(curation.hides(CHAT, 99L)).isFalse();
    }

    @Test
    void hidesFromGroupAdmins() {
        MerchantSubmissionCuration curation = new MerchantSubmissionCuration(
                new MerchantReviewGuard(List.of()), new PermissionChecker(Role.ADMIN));

        assertThat(curation.hides(CHAT, 42L))
                .as("群内管理员同样是「管事的人」")
                .isTrue();
    }

    /** fail-open：普通成员与身份不明者照常可见（收起是策展不是安全门）。 */
    @Test
    void plainMemberAndUnidentifiedViewerKeepTheFamily() {
        MerchantSubmissionCuration curation = new MerchantSubmissionCuration(
                new MerchantReviewGuard(List.of()), new PermissionChecker(Role.MEMBER));

        assertThat(curation.hides(CHAT, 99L)).isFalse();
        assertThat(curation.hides(CHAT, null)).isFalse();
    }

    /**
     * 判定器缺席（装配切片上下文的真实形状）：RBAC 半边判不了即不收，复核白名单半边照常——
     * 与 {@code MerchantConfiguration} 的 {@code ObjectProvider} 取舍配套。
     */
    @Test
    void degradesGracefullyWithoutPermissionChecker() {
        MerchantSubmissionCuration curation = new MerchantSubmissionCuration(
                new MerchantReviewGuard(List.of(777001L)), null);

        assertThat(curation.hides(CHAT, 777001L))
                .as("复核白名单半边不受判定器缺席影响")
                .isTrue();
        assertThat(curation.hides(CHAT, 42L))
                .as("RBAC 半边判不了即不收（fail-open）")
                .isFalse();
    }
}

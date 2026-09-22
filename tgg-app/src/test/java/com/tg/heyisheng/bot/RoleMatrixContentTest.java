package com.tg.heyisheng.bot;

import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.interaction.MenuCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「角色 × 权限 × 面」的**彻底划分**（真容器、真注册表，用户 2026-09-22 拍板：
 * 「对所有用户的权限进行一个彻底的划分，把所有角色的面彻底拆纯」）。
 *
 * <p>五类视角的可见命令集**逐条精确断言**——这就是划分本身（文档矩阵的机械形态），
 * 增删命令或改判据本类即变红，提醒人显式更新划分。判定同源链：
 * {@code MenuCatalog.visibleCommands}（RBAC / 接缝 / 策展三路）→ 各模块 Guard。
 *
 * <p><b>权限分叉的拍板记录</b>：商家管理权（含保证金结算这类**资金动作**）默认**不**并入群内管理员
 * ——USER-GUIDE 明文「四条互相独立的授权通道（不要在它们之间做类比）」。故纯群管的商家面为**空**
 * （自助链被策展收走、管理三件不在其授权内）——这是刻意的 fail-closed，不是缺陷：
 * 群管要管商家，加一行 {@code TGG_MERCHANT_REVIEWERS} 即可。若产品决定改为并权，
 * 本类的 adminSlice / merchantReviewerSlice 断言与 {@code MerchantMenuVisibility} 会同步变红。
 *
 * <p>联邦未在本容器启用（其命令需可解析对端公钥，由 {@code FederationMenuVisibilityWiringTest} 覆盖）；
 * 联邦管理面 = pending/approve/reject 三条，判据同构（{@code FederationMenuVisibility}）。
 */
@SpringBootTest(properties = {
        "tgg.listing.enabled=true",
        "tgg.merchant.enabled=true",
        "tgg.merchant.reviewers=777001,777003",
        "tgg.moderation.reviewers=777002",
        "tgg.permission.admins=-777010:42:ADMIN,-777010:777003:ADMIN"
})
class RoleMatrixContentTest {

    private static final long GROUP = -777010L;
    private static final long PLAIN = 99L;
    private static final long ADMIN = 42L;
    private static final long MERCHANT_REVIEWER = 777001L;
    private static final long MODERATION_REVIEWER = 777002L;
    private static final long COMBO = 777003L;   // 群管 + 商家复核双授权（并集回归）

    /** 通用自助面（全员共有；menu 自身不进目录）。 */
    private static final List<String> SELF_SERVICE = List.of(
            "echo", "help", "whoami", "status", "quiet_hours", "export_my_data", "case_appeal");
    /** 商家入驻自助链（「提交商家收录」——非管理侧可见）。 */
    private static final List<String> MERCHANT_SUBMISSION =
            List.of("merchant_apply", "merchant_status", "merchant_exit");
    /** 商家管理三件（「管理商家」——仅商家复核授权）。 */
    private static final List<String> MERCHANT_MANAGEMENT =
            List.of("merchant_review", "merchant_deposit", "merchant_settle");
    /** 群组收录的公共面。 */
    private static final List<String> LISTING_PUBLIC = List.of("listing_list", "listing_appeal");
    /** 群内管理面（群限定三条在列）。 */
    private static final List<String> GROUP_MANAGEMENT = List.of(
            "enable", "disable", "addword", "delword", "words", "rules", "unteach",
            "teach", "group_tag", "listing_add");
    /** 平台复核合规面。 */
    private static final List<String> REVIEW_WORKSPACE =
            List.of("review_list", "review_approve", "review_reject", "data_breach");

    @Autowired
    private MenuCatalog catalog;

    @Autowired
    private CommandRegistry registry;

    @SafeVarargs
    private static List<String> concat(List<String>... parts) {
        return java.util.Arrays.stream(parts).flatMap(List::stream).toList();
    }

    /** 普通用户：通用自助 + 商家入驻自助链 + 群组收录公共面——没有任何管理功能。 */
    @Test
    void plainUserSliceIsExactlySelfService() {
        assertThat(catalog.visibleCommands(GROUP, PLAIN, true))
                .containsExactlyInAnyOrderElementsOf(
                        concat(SELF_SERVICE, MERCHANT_SUBMISSION, LISTING_PUBLIC));
    }

    /**
     * 群内管理员：群管理面 + 通用自助 + 群组收录——**商家面为空**（刻意，见类注释的权限分叉拍板）。
     * 对称断言两向：自助链不见（策展）且管理三件不见（独立授权通道）。
     */
    @Test
    void groupAdminSliceIsExactlyGroupManagement() {
        List<String> visible = catalog.visibleCommands(GROUP, ADMIN, true);

        assertThat(visible)
                .containsExactlyInAnyOrderElementsOf(concat(SELF_SERVICE, GROUP_MANAGEMENT, LISTING_PUBLIC));
        assertThat(visible)
                .as("群管不看商家入驻自助链（策展）——与「不需要提交商家收录」一致")
                .doesNotContainAnyElementsOf(MERCHANT_SUBMISSION);
        assertThat(visible)
                .as("商家管理三件属独立授权通道：未授 TGG_MERCHANT_REVIEWERS 则不出现（fail-closed，不并权）")
                .doesNotContainAnyElementsOf(MERCHANT_MANAGEMENT);
    }

    /** 商家复核人：商家管理三件 + 通用自助 + 群组收录——入驻自助链被策展收走。 */
    @Test
    void merchantReviewerSliceIsExactlyMerchantManagement() {
        assertThat(catalog.visibleCommands(GROUP, MERCHANT_REVIEWER, true))
                .containsExactlyInAnyOrderElementsOf(
                        concat(SELF_SERVICE, MERCHANT_MANAGEMENT, LISTING_PUBLIC));
    }

    /**
     * 平台复核人：复核合规面 + 通用自助 + 群组收录 + **商家入驻自助链照常**——
     * 策展边界是「是否管理**商家域**」（商家复核 ∨ 群管，用户点名的两类管理员），
     * 平台复核人管的是复核域：他与商家域的关系就是普通用户（可能自己要入驻），
     * 自助链是他的个人自助功能，不属「别人的工作台」。
     */
    @Test
    void moderationReviewerSliceIsExactlyReviewWorkspace() {
        assertThat(catalog.visibleCommands(GROUP, MODERATION_REVIEWER, true))
                .containsExactlyInAnyOrderElementsOf(concat(
                        SELF_SERVICE, REVIEW_WORKSPACE, LISTING_PUBLIC, MERCHANT_SUBMISSION));
    }

    /** 复合角色（群管 + 商家复核双授权）：两面并集，自助链仍收走（策展不因多授权而失效）。 */
    @Test
    void combinedAdminAndMerchantReviewerUnionsTheirSlices() {
        assertThat(catalog.visibleCommands(GROUP, COMBO, true))
                .containsExactlyInAnyOrderElementsOf(concat(
                        SELF_SERVICE, GROUP_MANAGEMENT, LISTING_PUBLIC, MERCHANT_MANAGEMENT));
    }

    /** 分类拆纯：群组收录与商家收录是两枚分类（此前混在「收录商家」一枚里——用户点名的「模糊」）。 */
    @Test
    void categoriesSplitGroupListingFromMerchant() {
        assertThat(registry.categoryOf("listing_add").title())
                .as("listing 三条归「群组收录」")
                .isEqualTo("群组收录");
        assertThat(registry.categoryOf("listing_list").title()).isEqualTo("群组收录");
        assertThat(registry.categoryOf("merchant_apply").title())
                .as("merchant 六条归「商家收录」")
                .isEqualTo("商家收录");
        assertThat(registry.categoryOf("merchant_review").title()).isEqualTo("商家收录");
    }
}

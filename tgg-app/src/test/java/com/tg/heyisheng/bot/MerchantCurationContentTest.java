package com.tg.heyisheng.bot;

import com.tg.heyisheng.bot.core.interaction.MenuCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「收录商家」面的**策展不变量**（真容器、真注册表，用户 2026-09-22 拍板）：
 * 管事的人（商家复核白名单 / 群内管理员）的面板里不该混着「提交商家入驻」的自助链——
 * 面对他们是模糊的（他们管商家，不申请入驻）；他们的面里**只有「管理商家」**。
 * 普通成员视角不变（自助链照常可见）。
 *
 * <p><b>为什么放 tgg-app 容器级</b>（坑 17 的教训）：策展是「声明 + 装配 + 判据」三件套，
 * 单测夹具会自带声明自洽假绿——只有对**真实注册表与真实装配**断言，才能抓到
 * 「实现类漏了/装配漏接/判据写反」。与 {@code CommandMenuContentTest} 同一取舍。
 *
 * <p><b>只收展示，不动执行</b>（用户把「不存在」改口为「不需要」——准星就是展示层）：
 * {@code /merchant_apply} 等对管理员仍可直接键入执行，策展只影响 /menu·/help 的呈现。
 */
@SpringBootTest(properties = {
        "tgg.listing.enabled=true",
        "tgg.merchant.enabled=true",
        "tgg.merchant.reviewers=777001",
        "tgg.permission.admins=-777003:42:ADMIN"
})
class MerchantCurationContentTest {

    /** 与授权配置同框的测试群/身份（格式 <chatId>:<userId>:role 见上）。 */
    private static final long GROUP = -777003L;
    private static final long REVIEWER = 777001L;
    private static final long RBAC_ADMIN = 42L;
    private static final long MEMBER = 99L;

    /** 「提交商家收录」的自助链（入驻申请的这一家族）。 */
    private static final List<String> SUBMISSION =
            List.of("merchant_apply", "merchant_status", "merchant_exit");
    /** 「管理商家」：资质复核与保证金三件（商家复核白名单门控）。 */
    private static final List<String> MANAGEMENT =
            List.of("merchant_review", "merchant_deposit", "merchant_settle");

    @Autowired
    private MenuCatalog catalog;

    /** 商家复核人：面里只有「管理商家」，自助链整链收走。 */
    @Test
    void reviewerSeesOnlyMerchantManagement() {
        List<String> visible = catalog.visibleCommands(GROUP, REVIEWER, true);

        assertThat(visible)
                .as("复核人的「收录商家」面不该混着商家入驻自助链")
                .doesNotContainAnyElementsOf(SUBMISSION);
        assertThat(visible)
                .as("复核人的面里应有管理三件（资质复核/保证金）")
                .containsAll(MANAGEMENT);
    }

    /** 群内管理员（非复核人）同样是「管事的人」：自助链收走。 */
    @Test
    void rbacAdminAlsoHidesSubmissionFamily() {
        List<String> visible = catalog.visibleCommands(GROUP, RBAC_ADMIN, true);

        assertThat(visible)
                .as("管理员不需要「提交商家收录」的这些功能")
                .doesNotContainAnyElementsOf(SUBMISSION);
    }

    /** 普通成员视角不变：自助链照常可见，管理三件照常不出现。 */
    @Test
    void plainMemberKeepsSelfServiceFamily() {
        List<String> visible = catalog.visibleCommands(GROUP, MEMBER, true);

        assertThat(visible)
                .as("普通成员的商家自助链照常可见（策展只收管理者的面）")
                .containsAll(SUBMISSION);
        assertThat(visible)
                .as("管理三件仍按商家复核白名单门控，非复核人不出现")
                .doesNotContainAnyElementsOf(MANAGEMENT);
    }
}

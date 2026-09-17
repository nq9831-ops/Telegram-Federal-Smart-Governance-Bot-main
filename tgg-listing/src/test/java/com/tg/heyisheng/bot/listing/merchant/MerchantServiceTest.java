package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.credit.CreditService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家入驻状态机单测（不触库）。
 *
 * <p><b>本类守的是「非法迁移一律被拒」</b>：状态直接决定商家能否营业、能否被写入信用分账本，
 * 静默接受越级迁移（如未复核就 ACTIVE）会产出从未被审核通过的商家，且事后从数据上看不出来。
 * 每条迁移规则各有一个「正例 + 反例」。
 *
 * <p><b>信用分那句断言的是「协作契约」不是「产物」</b>：本层没有数据库，只能验证服务把
 * 正确的主体与分值交给了记账入口；分值真的落了库由 {@code MerchantOnboardingIT} 直查
 * {@code credit_scores} 断言（LESSONS：断言必须触碰真实行为）。
 */
class MerchantServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-17T03:00:00Z");
    private static final long OWNER = 42L;
    private static final long MERCHANT_ID = 7L;

    private final MerchantRepository repository = mock(MerchantRepository.class);
    private final CreditService creditService = mock(CreditService.class);
    private final MerchantProperties properties = new MerchantProperties();

    private MerchantService serviceWith(CreditService credit) {
        return new MerchantService(repository, properties, credit, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void repositoryReturns(Merchant merchant) {
        when(repository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
        when(repository.save(merchant)).thenReturn(merchant);
    }

    private static Merchant submitted() {
        return new Merchant(OWNER, "店", null, null, null, NOW);
    }

    private static Merchant depositPending() {
        Merchant merchant = withId(submitted(), MERCHANT_ID);
        merchant.beginReview(NOW);
        merchant.decide(Merchant.Status.APPROVED, NOW);
        merchant.markDepositPending(NOW);
        return merchant;
    }

    /**
     * 模拟「已持久化的实体」：真实链路上 {@code markActive} 的商家必然是从库里取出的（id 非空），
     * 而 {@code markActive} 要用<b>商家 id</b> 作为信用分主体——内存构造的实体没有 id，
     * 不补上就无法验证「主体 id 用的是商家 id 而不是 owner userId」。
     */
    private static Merchant withId(Merchant merchant, long id) {
        try {
            Field field = Merchant.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(merchant, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法为测试实体设置 id", ex);
        }
        return merchant;
    }

    @Test
    void submitRejectsBlankName() {
        assertThatThrownBy(() -> serviceWith(null).submit(OWNER, "   ", null, null, null))
                .as("名称是唯一 NOT NULL 的业务列，空名不应落库")
                .isInstanceOf(TggException.class);
    }

    @Test
    void submitTrimsNameAndStartsAsSubmitted() {
        ArgumentCaptor<Merchant> captor = ArgumentCaptor.forClass(Merchant.class);
        when(repository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        serviceWith(null).submit(OWNER, "  测试小铺  ", null, null, null);

        Merchant saved = captor.getValue();
        assertThat(saved.getName()).as("名称应被去除首尾空白").isEqualTo("测试小铺");
        assertThat(saved.getOwnerUserId()).isEqualTo(OWNER);
        assertThat(saved.getStatus()).isEqualTo(Merchant.Status.SUBMITTED.name());
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void decideWithoutBeginReviewIsRejected() {
        repositoryReturns(submitted());

        assertThatThrownBy(() -> serviceWith(null).decide(MERCHANT_ID, Merchant.Status.APPROVED))
                .as("未进入 UNDER_REVIEW 就落结论 = 未复核即通过，必须拒绝")
                .isInstanceOf(TggException.class);
    }

    @Test
    void beginReviewAfterRejectionIsRejected() {
        Merchant rejected = submitted();
        rejected.beginReview(NOW);
        rejected.decide(Merchant.Status.REJECTED, NOW);
        repositoryReturns(rejected);

        assertThatThrownBy(() -> serviceWith(null).beginReview(MERCHANT_ID))
                .as("REJECTED 是终态：重新入驻须新建条目，不能原地复活")
                .isInstanceOf(TggException.class);
    }

    @Test
    void needMoreCanBeResubmittedForReview() {
        Merchant needMore = submitted();
        needMore.beginReview(NOW);
        needMore.decide(Merchant.Status.NEED_MORE, NOW);
        repositoryReturns(needMore);

        assertThat(serviceWith(null).beginReview(MERCHANT_ID)).isPresent();
        assertThat(needMore.getStatus()).isEqualTo(Merchant.Status.UNDER_REVIEW.name());
    }

    @Test
    void markActiveBeforeDepositPendingIsRejected() {
        Merchant approved = submitted();
        approved.beginReview(NOW);
        approved.decide(Merchant.Status.APPROVED, NOW);
        repositoryReturns(approved);

        assertThatThrownBy(() -> serviceWith(creditService).markActive(MERCHANT_ID))
                .as("未缴保证金就 ACTIVE = 白嫖入驻，必须拒绝")
                .isInstanceOf(TggException.class);
    }

    @Test
    void markActiveInitializesMerchantCreditWithConfiguredScore() {
        Merchant merchant = depositPending();
        repositoryReturns(merchant);
        properties.setInitialScore(500);

        assertThat(serviceWith(creditService).markActive(MERCHANT_ID)).isPresent();

        assertThat(merchant.getStatus()).isEqualTo(Merchant.Status.ACTIVE.name());
        verify(creditService).ensureInitialized(CreditSubjectType.MERCHANT, MERCHANT_ID, 500);
    }

    @Test
    void markActiveWithoutCreditModuleStillActivatesButSkipsCredit() {
        Merchant merchant = depositPending();
        repositoryReturns(merchant);

        assertThat(serviceWith(null).markActive(MERCHANT_ID))
                .as("模块七未启用是合法配置：入驻照常完成，只跳过信用分（降级可观测）")
                .isPresent();
        assertThat(merchant.getStatus()).isEqualTo(Merchant.Status.ACTIVE.name());
    }

    @Test
    void findOpenByOwnerSkipsActiveAndRejected() {
        Merchant active = depositPending();
        active.markActive(NOW);
        Merchant rejected = submitted();
        rejected.beginReview(NOW);
        rejected.decide(Merchant.Status.REJECTED, NOW);
        when(repository.findByOwnerUserIdOrderByIdAsc(OWNER)).thenReturn(List.of(active, rejected));

        assertThat(serviceWith(null).findOpenByOwner(OWNER))
                .as("已入驻（可再开一家）与已驳回（可重新申请）都不算「在办」")
                .isEmpty();
    }

    @Test
    void findOpenByOwnerFindsSubmittedApplication() {
        Merchant submitted = submitted();
        when(repository.findByOwnerUserIdOrderByIdAsc(OWNER)).thenReturn(List.of(submitted));

        assertThat(serviceWith(null).findOpenByOwner(OWNER)).contains(submitted);
    }

    @Test
    void latestByOwnerReturnsNewestApplication() {
        Merchant older = submitted();
        Merchant newer = submitted();
        when(repository.findByOwnerUserIdOrderByIdAsc(OWNER)).thenReturn(List.of(older, newer));

        assertThat(serviceWith(null).latestByOwner(OWNER)).contains(newer);
    }

    @Test
    void unknownMerchantYieldsEmptyRatherThanException() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThat(serviceWith(null).find(99L)).isEmpty();
        assertThat(serviceWith(null).beginReview(99L)).isEmpty();
        assertThat(serviceWith(null).decide(99L, Merchant.Status.APPROVED)).isEmpty();
        assertThat(serviceWith(null).markDepositPending(99L)).isEmpty();
        assertThat(serviceWith(null).markActive(99L)).isEmpty();
    }
}

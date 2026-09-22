package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.common.exception.TggException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 联邦申诉裁定（模块八）。
 *
 * <p><b>守「不可自裁」</b>：申诉人本人不得裁定自己的申诉——与复核队列
 * （{@code ModerationReviewDecisionService}）、商家复核（{@code MerchantService}）
 * 同一约束。同类缺陷曾在三个模块里各自出现，故三处都对齐到「规则写在共用服务层」。
 *
 * <p><b>守「终态不可改写」</b>：已结案（APPROVED / REJECTED）的申诉不得再次裁定。
 * 否则 {@code /approve}、{@code /reject} 可对同一条申诉反复裁定，前次结论被静默覆盖——
 * 终态失去意义、审计链断裂。守卫写在服务层（{@code requirePending}）+ 实体层
 * （{@code FederationAppeal.decide} 状态机自守）。
 *
 * <p>本类此前**不存在**：{@code FederationAppealService.decide} 除命令处理器外没有任何测试调用方，
 * 「申诉人能自己批自己的解封」因此无人能发现。
 */
class FederationAppealServiceTest {

    private static final long APPEAL_ID = 3L;
    private static final long APPELLANT = 500L;
    private static final long OTHER_ADMIN = 501L;

    private final FederationAppealRepository repository = mock(FederationAppealRepository.class);
    private final FederationAppealService service = new FederationAppealService(repository);

    private static FederationAppeal appeal() {
        return new FederationAppeal(APPELLANT, FederationAppealService.TYPE_FEDBAN_UNBAN, "误封",
                Instant.parse("2026-09-20T00:00:00Z"));
    }

    @Test
    void appellantCannotDecideOwnAppeal() {
        FederationAppeal appeal = appeal();
        when(repository.findById(APPEAL_ID)).thenReturn(Optional.of(appeal));

        assertThatThrownBy(() -> service.decide(APPEAL_ID, true, APPELLANT))
                .as("申诉人本人不得裁定自己的申诉（否则等于自己给自己解封）")
                .isInstanceOf(TggException.class)
                .hasMessageContaining("不能裁定自己的申诉");

        assertThat(appeal.getStatus()).as("被拒的自裁不得改动状态")
                .isEqualTo(FederationAppeal.Status.PENDING.name());
        verify(repository, never()).save(any());
    }

    @Test
    void otherAdminCanDecide() {
        FederationAppeal appeal = appeal();
        when(repository.findById(APPEAL_ID)).thenReturn(Optional.of(appeal));

        assertThat(service.decide(APPEAL_ID, true, OTHER_ADMIN)).isPresent();

        assertThat(appeal.getStatus()).isEqualTo(FederationAppeal.Status.APPROVED.name());
        verify(repository).save(appeal);
    }

    @Test
    void rejectionAlsoWorksForAnotherAdmin() {
        FederationAppeal appeal = appeal();
        when(repository.findById(APPEAL_ID)).thenReturn(Optional.of(appeal));

        service.decide(APPEAL_ID, false, OTHER_ADMIN);

        assertThat(appeal.getStatus()).isEqualTo(FederationAppeal.Status.REJECTED.name());
    }

    @Test
    void approvedAppealCannotBeDecidedAgain() {
        FederationAppeal appeal = appeal();
        appeal.decide(FederationAppeal.Status.APPROVED);
        when(repository.findById(APPEAL_ID)).thenReturn(Optional.of(appeal));

        assertThatThrownBy(() -> service.decide(APPEAL_ID, false, OTHER_ADMIN))
                .as("已结案（APPROVED）的申诉不得再次裁定——否则前次结论被静默覆盖")
                .isInstanceOf(TggException.class)
                .hasMessageContaining("已结案");

        assertThat(appeal.getStatus()).as("被拒的再裁定不得改动已结案的结论")
                .isEqualTo(FederationAppeal.Status.APPROVED.name());
        verify(repository, never()).save(any());
    }

    @Test
    void rejectedAppealCannotBeDecidedAgain() {
        FederationAppeal appeal = appeal();
        appeal.decide(FederationAppeal.Status.REJECTED);
        when(repository.findById(APPEAL_ID)).thenReturn(Optional.of(appeal));

        assertThatThrownBy(() -> service.decide(APPEAL_ID, true, OTHER_ADMIN))
                .as("已结案（REJECTED）的申诉不得再次裁定——否则前次结论被静默覆盖")
                .isInstanceOf(TggException.class)
                .hasMessageContaining("已结案");

        assertThat(appeal.getStatus()).as("被拒的再裁定不得改动已结案的结论")
                .isEqualTo(FederationAppeal.Status.REJECTED.name());
        verify(repository, never()).save(any());
    }

    @Test
    void unknownAppealYieldsEmptyRatherThanException() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.decide(99L, false, OTHER_ADMIN)).isEmpty();
    }
}

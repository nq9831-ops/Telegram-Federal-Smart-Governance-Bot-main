package com.tg.heyisheng.bot.admin.approval;

import com.tg.heyisheng.bot.core.audit.AuditEntry;
import com.tg.heyisheng.bot.core.audit.AuditService;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewDecisionService;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewItem;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository;
import com.tg.heyisheng.bot.core.moderation.ReviewStatus;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 审批中心裁决侧（模块十一 §12.1 / §12.2 约束）。
 *
 * <p>守的重点是<b>「审批人不可审批自己的案件」</b>：它必须发生在<b>进入裁决之前</b>——
 * 若先裁决再检查，解封/禁言动作已经发出，再拒绝就只是个事后声明。
 */
class ApprovalCommandServiceTest {

    private static final long OPERATOR = 777L;
    private static final long OFFENDER = 888L;
    private static final long ITEM_ID = 42L;

    private final ModerationReviewRepository repository = mock(ModerationReviewRepository.class);
    private final ModerationReviewDecisionService decisions = mock(ModerationReviewDecisionService.class);
    private final AuditService audit = mock(AuditService.class);

    private final ApprovalCommandService service =
            new ApprovalCommandService(repository, decisions, audit);

    private ModerationReviewItem itemOwnedBy(Long subjectId) {
        ModerationReviewItem entity =
                new ModerationReviewItem(-100L, subjectId, 1, List.of("HARD_CSAM"), RiskLevel.HIGH, true);
        set(entity, "id", ITEM_ID);
        return entity;
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = ModerationReviewItem.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("测试夹具无法写入字段 " + field, ex);
        }
    }

    private void decidedAs(ModerationReviewDecisionService.Outcome.Result result, ReviewStatus status) {
        when(decisions.decide(anyLong(), any(), anyLong(), any()))
                .thenReturn(new ModerationReviewDecisionService.Outcome(result, status));
    }

    /**
     * 「不可自审」已下沉到共用裁决服务；本类只把它的拒绝映射成结果并留痕。
     *
     * <p>此前本类自己判一次、而 Telegram 命令不判——同一规则两份实现，于是「群里能审自己的案子、
     * 后台不能审」。「不改状态、不发动作」那个不变量现在由
     * {@code ModerationReviewDecisionServiceTest#subjectCannotDecideOwnCase} 守。
     */
    @Test
    void selfDecisionRefusalFromTheSharedDeciderIsMappedAndAudited() {
        when(repository.findById(ITEM_ID)).thenReturn(Optional.of(itemOwnedBy(OPERATOR)));
        decidedAs(ModerationReviewDecisionService.Outcome.Result.SELF_DECISION_FORBIDDEN,
                ReviewStatus.PENDING);

        ApprovalCommandService.Outcome outcome =
                service.decide(ITEM_ID, ReviewStatus.REJECTED, OPERATOR, "自己的案子");

        assertThat(outcome.result()).isEqualTo(ApprovalCommandService.Result.SELF_DECISION_FORBIDDEN);
        verify(audit).record(eq(OPERATOR), eq(ApprovalCommandService.AUDIT_ACTION), eq(ITEM_ID),
                eq(AuditEntry.Outcome.FAILURE), eq("self-decision-forbidden"));
    }

    /**
     * 本类<b>不得</b>再自己判自审：即使当事人就是操作人，也必须交给共用服务判
     * ——规则只允许有一份实现，否则两端迟早再次漂移。
     */
    @Test
    void adapterDelegatesEvenWhenTheSubjectIsTheOperator() {
        when(repository.findById(ITEM_ID)).thenReturn(Optional.of(itemOwnedBy(OPERATOR)));
        decidedAs(ModerationReviewDecisionService.Outcome.Result.DECIDED, ReviewStatus.APPROVED);

        service.decide(ITEM_ID, ReviewStatus.APPROVED, OPERATOR, null);

        verify(decisions).decide(ITEM_ID, ReviewStatus.APPROVED, OPERATOR, null);
    }

    @Test
    void decidingSomeoneElsesCaseReachesTheDecider() {
        when(repository.findById(ITEM_ID)).thenReturn(Optional.of(itemOwnedBy(OFFENDER)));
        decidedAs(ModerationReviewDecisionService.Outcome.Result.DECIDED, ReviewStatus.APPROVED);

        ApprovalCommandService.Outcome outcome =
                service.decide(ITEM_ID, ReviewStatus.APPROVED, OPERATOR, "证据确凿");

        assertThat(outcome.result()).isEqualTo(ApprovalCommandService.Result.DECIDED);
        verify(decisions).decide(ITEM_ID, ReviewStatus.APPROVED, OPERATOR, "证据确凿");
    }

    @Test
    void unknownIdIsNotFoundAndNotAudited() {
        when(repository.findById(ITEM_ID)).thenReturn(Optional.empty());

        ApprovalCommandService.Outcome outcome =
                service.decide(ITEM_ID, ReviewStatus.APPROVED, OPERATOR, null);

        assertThat(outcome.result()).isEqualTo(ApprovalCommandService.Result.NOT_FOUND);
        verifyNoInteractions(decisions);
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void successfulDecisionIsAuditedAsSuccess() {
        when(repository.findById(ITEM_ID)).thenReturn(Optional.of(itemOwnedBy(OFFENDER)));
        decidedAs(ModerationReviewDecisionService.Outcome.Result.DECIDED, ReviewStatus.REJECTED);

        service.decide(ITEM_ID, ReviewStatus.REJECTED, OPERATOR, "误封");

        verify(audit).record(eq(OPERATOR), eq(ApprovalCommandService.AUDIT_ACTION), eq(ITEM_ID),
                eq(AuditEntry.Outcome.SUCCESS), any());
    }

    @Test
    void idempotentHitIsAuditedAsFailureBecauseNothingChanged() {
        when(repository.findById(ITEM_ID)).thenReturn(Optional.of(itemOwnedBy(OFFENDER)));
        decidedAs(ModerationReviewDecisionService.Outcome.Result.ALREADY_DECIDED, ReviewStatus.APPROVED);

        ApprovalCommandService.Outcome outcome =
                service.decide(ITEM_ID, ReviewStatus.APPROVED, OPERATOR, null);

        assertThat(outcome.result()).isEqualTo(ApprovalCommandService.Result.ALREADY_DECIDED);
        verify(audit).record(eq(OPERATOR), eq(ApprovalCommandService.AUDIT_ACTION), eq(ITEM_ID),
                eq(AuditEntry.Outcome.FAILURE), any());
    }
}

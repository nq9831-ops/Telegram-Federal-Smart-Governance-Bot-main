package com.tg.heyisheng.bot.admin.approval;

import com.tg.heyisheng.bot.core.audit.ActorType;
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
import static org.mockito.ArgumentMatchers.isNull;
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
 *
 * <p><b>主体带类型</b>：后台账号（{@link ActorType#ADMIN_ACCOUNT}）<b>不是 TG 用户</b>，
 * 故传给共用裁决服务的 operator 必须是 {@code null}——否则账号 id 与 Telegram userId
 * 数值撞车会被误判为「当事人在自审」。本条由
 * {@link #adminAccountNeverParticipatesInSelfDecision} 钉住。
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
        when(decisions.decide(anyLong(), any(), any(), any()))
                .thenReturn(new ModerationReviewDecisionService.Outcome(result, status));
    }

    /**
     * 「不可自审」已下沉到共用裁决服务；本类只把它的拒绝映射成结果并留痕。
     */
    @Test
    void selfDecisionRefusalFromTheSharedDeciderIsMappedAndAudited() {
        when(repository.findById(ITEM_ID)).thenReturn(Optional.of(itemOwnedBy(OPERATOR)));
        decidedAs(ModerationReviewDecisionService.Outcome.Result.SELF_DECISION_FORBIDDEN,
                ReviewStatus.PENDING);

        ApprovalCommandService.Outcome outcome =
                service.decide(ITEM_ID, ReviewStatus.REJECTED, ActorType.TG_USER, OPERATOR, "自己的案子");

        assertThat(outcome.result()).isEqualTo(ApprovalCommandService.Result.SELF_DECISION_FORBIDDEN);
        verify(audit).record(eq(ActorType.TG_USER), eq(OPERATOR), eq(ApprovalCommandService.AUDIT_ACTION),
                eq(ITEM_ID), eq(AuditEntry.Outcome.FAILURE), eq("self-decision-forbidden"));
    }

    /**
     * 本类<b>不得</b>再自己判自审：即使当事人就是操作人，也必须交给共用服务判
     * ——规则只允许有一份实现，否则两端迟早再次漂移。
     */
    @Test
    void adapterDelegatesEvenWhenTheSubjectIsTheOperator() {
        when(repository.findById(ITEM_ID)).thenReturn(Optional.of(itemOwnedBy(OPERATOR)));
        decidedAs(ModerationReviewDecisionService.Outcome.Result.DECIDED, ReviewStatus.APPROVED);

        service.decide(ITEM_ID, ReviewStatus.APPROVED, ActorType.TG_USER, OPERATOR, null);

        verify(decisions).decide(ITEM_ID, ReviewStatus.APPROVED, OPERATOR, null);
    }

    /**
     * <b>关键不变量</b>：后台账号审批时，传给共用裁决服务的 operator 必须是 {@code null}
     * ——后台账号不是 TG 用户，其 id 与 userId 数值空间重叠，若当 userId 传下去会误判自审。
     * 审计仍记真实主体（账号）。
     */
    @Test
    void adminAccountNeverParticipatesInSelfDecision() {
        when(repository.findById(ITEM_ID)).thenReturn(Optional.of(itemOwnedBy(OPERATOR)));
        decidedAs(ModerationReviewDecisionService.Outcome.Result.DECIDED, ReviewStatus.APPROVED);

        service.decide(ITEM_ID, ReviewStatus.APPROVED, ActorType.ADMIN_ACCOUNT, OPERATOR, null);

        verify(decisions).decide(eq(ITEM_ID), eq(ReviewStatus.APPROVED), isNull(), isNull());
        verify(audit).record(eq(ActorType.ADMIN_ACCOUNT), eq(OPERATOR),
                eq(ApprovalCommandService.AUDIT_ACTION), eq(ITEM_ID), eq(AuditEntry.Outcome.SUCCESS), any());
    }

    @Test
    void decidingSomeoneElsesCaseReachesTheDecider() {
        when(repository.findById(ITEM_ID)).thenReturn(Optional.of(itemOwnedBy(OFFENDER)));
        decidedAs(ModerationReviewDecisionService.Outcome.Result.DECIDED, ReviewStatus.APPROVED);

        ApprovalCommandService.Outcome outcome =
                service.decide(ITEM_ID, ReviewStatus.APPROVED, ActorType.ADMIN_ACCOUNT, OPERATOR, "证据确凿");

        assertThat(outcome.result()).isEqualTo(ApprovalCommandService.Result.DECIDED);
        verify(decisions).decide(ITEM_ID, ReviewStatus.APPROVED, null, "证据确凿");
    }

    @Test
    void unknownIdIsNotFoundAndNotAudited() {
        when(repository.findById(ITEM_ID)).thenReturn(Optional.empty());

        ApprovalCommandService.Outcome outcome =
                service.decide(ITEM_ID, ReviewStatus.APPROVED, ActorType.ADMIN_ACCOUNT, OPERATOR, null);

        assertThat(outcome.result()).isEqualTo(ApprovalCommandService.Result.NOT_FOUND);
        verifyNoInteractions(decisions);
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void successfulDecisionIsAuditedAsSuccess() {
        when(repository.findById(ITEM_ID)).thenReturn(Optional.of(itemOwnedBy(OFFENDER)));
        decidedAs(ModerationReviewDecisionService.Outcome.Result.DECIDED, ReviewStatus.REJECTED);

        service.decide(ITEM_ID, ReviewStatus.REJECTED, ActorType.ADMIN_ACCOUNT, OPERATOR, "误封");

        verify(audit).record(eq(ActorType.ADMIN_ACCOUNT), eq(OPERATOR), eq(ApprovalCommandService.AUDIT_ACTION),
                eq(ITEM_ID), eq(AuditEntry.Outcome.SUCCESS), any());
    }

    @Test
    void idempotentHitIsAuditedAsFailureBecauseNothingChanged() {
        when(repository.findById(ITEM_ID)).thenReturn(Optional.of(itemOwnedBy(OFFENDER)));
        decidedAs(ModerationReviewDecisionService.Outcome.Result.ALREADY_DECIDED, ReviewStatus.APPROVED);

        ApprovalCommandService.Outcome outcome =
                service.decide(ITEM_ID, ReviewStatus.APPROVED, ActorType.ADMIN_ACCOUNT, OPERATOR, null);

        assertThat(outcome.result()).isEqualTo(ApprovalCommandService.Result.ALREADY_DECIDED);
        verify(audit).record(eq(ActorType.ADMIN_ACCOUNT), eq(OPERATOR), eq(ApprovalCommandService.AUDIT_ACTION),
                eq(ITEM_ID), eq(AuditEntry.Outcome.FAILURE), any());
    }
}

package com.tg.heyisheng.bot.admin.approval;

import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.audit.AuditEntry;
import com.tg.heyisheng.bot.core.audit.AuditService;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewDecisionService;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository;
import com.tg.heyisheng.bot.core.moderation.ReviewStatus;

/**
 * 审批中心的<b>裁决侧</b>（模块十一 §12.1 第 2–3 步）。
 *
 * <p><b>它只是薄适配，不重写裁决</b>：真正的裁决（<b>不可自审</b>、幂等、备注长度校验、解封/禁言动作）
 * 在 {@link ModerationReviewDecisionService#decide}，与 Telegram 端的 {@code /review_approve}
 * 共用同一份逻辑。若在这里另写一遍，两条入口的语义必然漂移——这正是本项目反复警惕的分裂。
 *
 * <p><b>主体是 (类型, id) 二元组</b>：调用方从会话取主体类型与 id 传入。
 * <b>为什么后台账号不参与「不可自审」</b>：该规则的判据是「被判定消息的发布者 == 裁决人」，
 * 针对的是「当事人不能审自己的案子」。而后台账号（超管/操作员）<b>不是 TG 用户</b>，
 * 其账号 id 与 Telegram userId 落在同一数值空间——若把账号 id 当 userId 传下去，
 * id 恰好相等的账号会被误判为「当事人在自审」。故对 {@link ActorType#ADMIN_ACCOUNT}
 * 传 {@code null}（语义即「无 TG 身份，不参与自审」）。审批人的权威记录在审计表
 * （带 {@code actor_type}，见 {@link #AUDIT_ACTION}）。
 */
public class ApprovalCommandService {

    /** 裁决动作在审计表中的标识。 */
    static final String AUDIT_ACTION = "admin.approval.decide";

    /** 裁决结果。 */
    public enum Result {
        /** 本次成功裁决。 */
        DECIDED,
        /** 该项已有终态结论（幂等，未重复处置）。 */
        ALREADY_DECIDED,
        /** 队列中无此 id。 */
        NOT_FOUND,
        /** 审批人正是该案件的当事人——拒绝。 */
        SELF_DECISION_FORBIDDEN
    }

    public record Outcome(Result result, ReviewStatus status) {
    }

    private final ModerationReviewRepository repository;
    private final ModerationReviewDecisionService decisions;
    private final AuditService audit;

    public ApprovalCommandService(ModerationReviewRepository repository,
                                  ModerationReviewDecisionService decisions,
                                  AuditService audit) {
        this.repository = repository;
        this.decisions = decisions;
        this.audit = audit;
    }

    /**
     * 裁决一条待办。
     *
     * @param id        队列项 id
     * @param decision  裁决结论（APPROVED / REJECTED）
     * @param actorType 主体类型（来自会话）
     * @param subjectId 主体 id（账号 id 或 TG userId）
     * @param reason    裁决理由；长度上限由 core 的裁决服务把关（超长会抛 {@code TggException}）
     */
    public Outcome decide(long id, ReviewStatus decision, ActorType actorType, Long subjectId,
                          String reason) {
        if (repository.findById(id).isEmpty()) {
            // 不审计「找不到」：审计表记的是动作，未发生的动作没有留痕价值
            return new Outcome(Result.NOT_FOUND, null);
        }

        // 「不可自审」只在 TG 主体下有意义：后台账号不是 TG 用户，传 null 使其不参与该判定，
        // 同时避免账号 id 与 userId 数值撞车导致的误判（见类注释）。
        Long selfDecisionSubject = actorType == ActorType.TG_USER ? subjectId : null;

        // 「不可自审」的实现不在这里——它已下沉到共用裁决服务，Telegram 的 /review_* 与
        // Web 后台走同一份规则。本类只把结果映射成 HTTP 语义并留痕。
        ModerationReviewDecisionService.Outcome outcome =
                decisions.decide(id, decision, selfDecisionSubject, reason);
        Result result = switch (outcome.result()) {
            case NOT_FOUND -> Result.NOT_FOUND;
            case ALREADY_DECIDED -> Result.ALREADY_DECIDED;
            case SELF_DECISION_FORBIDDEN -> Result.SELF_DECISION_FORBIDDEN;
            case DECIDED -> Result.DECIDED;
        };

        // 自审被拒与幂等命中都记 FAILURE：本次并未改变任何状态，与「成功执行」区分开
        String detail = result == Result.SELF_DECISION_FORBIDDEN
                ? "self-decision-forbidden"
                : "decision=" + decision + " result=" + result;
        audit.record(actorType, subjectId, AUDIT_ACTION, id,
                result == Result.DECIDED ? AuditEntry.Outcome.SUCCESS : AuditEntry.Outcome.FAILURE,
                detail);
        return new Outcome(result, outcome.status());
    }
}

package com.tg.heyisheng.bot.admin.approval;

import com.tg.heyisheng.bot.core.audit.AuditEntry;
import com.tg.heyisheng.bot.core.audit.AuditService;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewDecisionService;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewItem;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository;
import com.tg.heyisheng.bot.core.moderation.ReviewStatus;

import java.util.Optional;

/**
 * 审批中心的<b>裁决侧</b>（模块十一 §12.1 第 2–3 步）。
 *
 * <p><b>它只是薄适配，不重写裁决</b>：真正的裁决（幂等、备注长度校验、解封/禁言动作）在
 * {@link ModerationReviewDecisionService#decide}，与 Telegram 端的 {@code /review_approve} 共用同一份逻辑。
 * 若在这里另写一遍，两条入口的语义必然漂移——这正是本项目反复警惕的分裂。
 *
 * <p><b>本类只加两件 Web 侧才有的东西</b>：
 * <ol>
 *   <li><b>「审批人不可审批自己的案件」</b>（§12.2 约束）——既有 {@code /review_*} 无此校验，
 *       故只在 Web 侧施加，不改动既有命令的行为；</li>
 *   <li><b>审计留痕</b>（§12.2 第 7 项）——记「谁、对哪条、下了什么结论、结果如何」，
 *       <b>不记消息正文</b>（队列本就不含正文）。</li>
 * </ol>
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
     * @param operator 操作者 userId（已由 {@code AdminAuthFilter} 验明在复核人白名单内）
     * @param reason   裁决理由；长度上限由 core 的裁决服务把关（超长会抛 {@code TggException}）
     */
    public Outcome decide(long id, ReviewStatus decision, Long operator, String reason) {
        Optional<ModerationReviewItem> found = repository.findById(id);
        if (found.isEmpty()) {
            // 不审计「找不到」：审计表记的是动作，未发生的动作没有留痕价值
            return new Outcome(Result.NOT_FOUND, null);
        }
        ModerationReviewItem item = found.get();

        if (item.getUserId() != null && item.getUserId().equals(operator)) {
            audit.record(operator, AUDIT_ACTION, id, AuditEntry.Outcome.FAILURE,
                    "self-decision-forbidden");
            return new Outcome(Result.SELF_DECISION_FORBIDDEN, item.getStatus());
        }

        ModerationReviewDecisionService.Outcome outcome = decisions.decide(id, decision, operator, reason);
        Result result = switch (outcome.result()) {
            case NOT_FOUND -> Result.NOT_FOUND;
            case ALREADY_DECIDED -> Result.ALREADY_DECIDED;
            case DECIDED -> Result.DECIDED;
        };

        // 幂等命中记 FAILURE：本次并未改变任何状态，与「成功执行」区分开
        audit.record(operator, AUDIT_ACTION, id,
                result == Result.DECIDED ? AuditEntry.Outcome.SUCCESS : AuditEntry.Outcome.FAILURE,
                "decision=" + decision + " result=" + result);
        return new Outcome(result, outcome.status());
    }
}

package com.tg.heyisheng.bot.admin.dangerous;

import com.tg.heyisheng.bot.admin.system.RestartAction;
import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.audit.AuditEntry;
import com.tg.heyisheng.bot.core.audit.AuditService;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 危险动作双人复核（模块十一 · 护栏）。
 *
 * <p><b>核心不变量——发起人 ≠ 批准人</b>：{@link #approve} 拒绝「发起人自批」。这正是「双人」的定义，
 * 也是它区别于"一个人弹两次确认"的地方。
 *
 * <p><b>批准后立即执行</b>：批准不是"再确认一次"，而是<b>唯一</b>会触发动作的路径——
 * 所以操作员<b>无法</b>绕过复核单方面重启（其直接重启入口已被收紧，见 {@code SystemController}）。
 *
 * <p><b>超管不经此路</b>：超管可直接重启（最高权威），否则单超管结构下会死锁。
 */
public class DangerousActionService {

    /** 复核结果。 */
    public enum Result {
        /** 已创建待批准请求。 */
        CREATED,
        /** 无此请求。 */
        NOT_FOUND,
        /** 该请求已有终态（幂等，不重复执行）。 */
        ALREADY_DECIDED,
        /** <b>发起人自批被拒</b>——「双人」约束。 */
        SAME_SUBJECT_FORBIDDEN,
        /** 已批准并执行。 */
        APPROVED,
        /** 已拒绝。 */
        REJECTED,
        /**
         * <b>未执行</b>：动作门控（{@code restart-enabled}）未开启。
         * 请求<b>保持 PENDING</b>——部署方开启开关后仍可批准，与直接入口的 409 语义一致。
         */
        DISABLED
    }

    public record Outcome(Result result, Long requestId, DangerousActionRequest.Status status) {
    }

    static final String AUDIT_REQUEST = "admin.dangerous.request";
    static final String AUDIT_APPROVE = "admin.dangerous.approve";
    static final String AUDIT_REJECT = "admin.dangerous.reject";

    /** 与直接重启入口同一个门控键——部署方关掉它即表示「本部署不接受经 Web 重启」。 */
    static final String RESTART_ENABLED_KEY = "tgg.admin.restart-enabled";

    private final DangerousActionRequestRepository requests;
    private final AuditService audit;
    private final RestartAction restartAction;
    private final RuntimeConfigService config;
    private final Clock clock;

    public DangerousActionService(DangerousActionRequestRepository requests, AuditService audit,
                                  RestartAction restartAction, RuntimeConfigService config, Clock clock) {
        this.requests = requests;
        this.audit = audit;
        this.restartAction = restartAction;
        this.config = config;
        this.clock = clock;
    }

    /** 发起一条待批准请求（通常是操作员）。 */
    @Transactional
    public Outcome request(DangerousActionRequest.ActionType type, ActorType byType, Long byId) {
        DangerousActionRequest saved = requests.save(
                new DangerousActionRequest(type, byType, byId, clock.instant()));
        audit.record(byType, byId, AUDIT_REQUEST, saved.getId(), AuditEntry.Outcome.SUCCESS,
                "request " + type);
        return new Outcome(Result.CREATED, saved.getId(), DangerousActionRequest.Status.PENDING);
    }

    /**
     * 批准并执行。
     *
     * <p>调用方（控制器）须已校验批准人是超管；本方法校验<b>发起人 ≠ 批准人</b>、门控、以及终态幂等。
     *
     * <p><b>两条顺序性约束（都不是可选的）</b>：
     * <ol>
     *   <li><b>门控先于状态变更</b>：未启用 {@code restart-enabled} 时直接拒绝、<b>不改状态</b>。
     *       否则会出现「审计记 SUCCESS、状态已 APPROVED，动作却没发生」的<b>假成功</b>，
     *       且此后一律 409、无法补救。</li>
     *   <li><b>抢占式裁决</b>：用条件更新（{@code WHERE status='PENDING'}）而非「读-改-写」，
     *       并发双击时数据库保证只有一个事务能改成 APPROVED —— 「批准是唯一触发路径」蕴含
     *       <b>至多执行一次</b>。</li>
     * </ol>
     */
    @Transactional
    public Outcome approve(Long requestId, Long accountId) {
        Optional<DangerousActionRequest> found = requests.findById(requestId);
        if (found.isEmpty()) {
            return new Outcome(Result.NOT_FOUND, requestId, null);
        }
        DangerousActionRequest request = found.get();
        if (!request.isPending()) {
            return new Outcome(Result.ALREADY_DECIDED, requestId, request.getStatus());
        }
        if (request.wasRequestedBy(ActorType.ADMIN_ACCOUNT, accountId)) {
            // 「双人」约束：同一主体不得既发起又批准
            audit.record(ActorType.ADMIN_ACCOUNT, accountId, AUDIT_APPROVE, requestId,
                    AuditEntry.Outcome.FAILURE, "self-approve-forbidden");
            return new Outcome(Result.SAME_SUBJECT_FORBIDDEN, requestId, request.getStatus());
        }
        // 门控前置：未启用即拒绝，**不动状态**（可稍后启用再批）
        if (!config.getBoolean(RESTART_ENABLED_KEY, false)) {
            audit.record(ActorType.ADMIN_ACCOUNT, accountId, AUDIT_APPROVE, requestId,
                    AuditEntry.Outcome.FAILURE, "restart-disabled");
            return new Outcome(Result.DISABLED, requestId, request.getStatus());
        }
        // 抢占：只有抢到 PENDING→APPROVED 的那个事务才执行动作
        int claimed = requests.approveIfPending(requestId, ActorType.ADMIN_ACCOUNT.name(), accountId,
                null, clock.instant());
        if (claimed == 0) {
            return new Outcome(Result.ALREADY_DECIDED, requestId, DangerousActionRequest.Status.APPROVED);
        }
        audit.record(ActorType.ADMIN_ACCOUNT, accountId, AUDIT_APPROVE, requestId,
                AuditEntry.Outcome.SUCCESS, "approved " + request.getActionType());
        execute(request);
        return new Outcome(Result.APPROVED, requestId, DangerousActionRequest.Status.APPROVED);
    }

    /** 拒绝（不执行）。 */
    @Transactional
    public Outcome reject(Long requestId, Long accountId, String note) {
        Optional<DangerousActionRequest> found = requests.findById(requestId);
        if (found.isEmpty()) {
            return new Outcome(Result.NOT_FOUND, requestId, null);
        }
        if (!found.get().isPending()) {
            return new Outcome(Result.ALREADY_DECIDED, requestId, found.get().getStatus());
        }
        int claimed = requests.rejectIfPending(requestId, ActorType.ADMIN_ACCOUNT.name(), accountId,
                note, clock.instant());
        if (claimed == 0) {
            return new Outcome(Result.ALREADY_DECIDED, requestId, DangerousActionRequest.Status.REJECTED);
        }
        audit.record(ActorType.ADMIN_ACCOUNT, accountId, AUDIT_REJECT, requestId,
                AuditEntry.Outcome.SUCCESS, "rejected");
        return new Outcome(Result.REJECTED, requestId, DangerousActionRequest.Status.REJECTED);
    }

    /** 待批准队列。 */
    public List<DangerousActionRequest> pending() {
        return requests.findByStatusOrderByIdDesc(DangerousActionRequest.Status.PENDING);
    }

    public Optional<DangerousActionRequest> find(Long id) {
        return requests.findById(id);
    }

    /**
     * 真正执行——当前只有重启一种。
     *
     * <p><b>门控不在这里</b>：它已在 {@link #approve} 中<b>前置</b>校验。放在这里会导致
     * 「状态已改、审计已记 SUCCESS，函数却静默 return」的假成功。
     */
    private void execute(DangerousActionRequest request) {
        if (request.getActionType() == DangerousActionRequest.ActionType.RESTART && restartAction != null) {
            restartAction.restart();
        }
    }
}

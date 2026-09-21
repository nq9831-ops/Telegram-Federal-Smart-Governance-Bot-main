package com.tg.heyisheng.bot.core.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 审计服务（模块十 §11.2）：把「谁、对什么、做了什么、结果如何」记入审计表。
 *
 * <p><b>失败取舍（重要，且是刻意的）</b>：写入失败**只记 ERROR、不中断业务**。
 * 理由：审计是旁挂能力，让它把业务打断会造成更大的可用性损害（例如一条审计写不进去导致封禁动作回滚）。
 * 但「不中断」绝不等于「静默」——失败会打 ERROR 并带异常栈，且这是**审计本身的降级**，
 * 需部署侧告警（已记入部署清单）。若要强一致（审计写不进就不许动作），须改造为同事务写入。
 *
 * <p><b>主体是 (类型, id) 二元组</b>：核心签名要求显式传 {@link ActorType}；
 * 旧签名（不带类型）保留为便捷重载，委托 {@link ActorType#TG_USER}——命令路径与审核路径
 * 的主体都是 TG 用户，语义等价（见 {@link ActorType} 与 V21 迁移）。
 * <b>后台路径（超管/操作员）必须用显式类型签名传 {@link ActorType#ADMIN_ACCOUNT}</b>。
 *
 * <p><b>不含消息正文</b>：本服务只接受动作标识与结论。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final Clock clock;

    @Autowired
    public AuditService(AuditLogRepository repository) {
        this(repository, Clock.systemUTC());
    }

    AuditService(AuditLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 记一条审计（便捷重载：默认主体类型 {@link ActorType#TG_USER}，无案件号）。
     *
     * <p>供命令路径与审核路径使用——这些路径的主体恒为 TG 用户。
     */
    @Transactional
    public void record(Long actorId, String action, Long target,
                       AuditEntry.Outcome outcome, String detail) {
        record(ActorType.TG_USER, actorId, action, target, null, outcome, detail);
    }

    /**
     * 记一条带<b>案件号</b>的审计（便捷重载：默认 {@link ActorType#TG_USER}）。
     *
     * @param caseId 关联案件号；无则 {@code null}
     */
    @Transactional
    public void record(Long actorId, String action, Long target, Long caseId,
                       AuditEntry.Outcome outcome, String detail) {
        record(ActorType.TG_USER, actorId, action, target, caseId, outcome, detail);
    }

    /**
     * 记一条审计（显式主体类型，无案件号）。
     *
     * @param actorType 主体类型——后台操作传 {@link ActorType#ADMIN_ACCOUNT}
     * @param actorId   与本次动作相关的主体 id
     * @param action    动作标识（如命令处理器类名、{@code admin.config.update}）
     * @param target    作用对象（如 chatId / 被判主体 id）；无则 {@code null}
     * @param outcome   结果
     * @param detail    补充说明（<b>不含消息正文</b>）；可为 {@code null}
     */
    @Transactional
    public void record(ActorType actorType, Long actorId, String action, Long target,
                       AuditEntry.Outcome outcome, String detail) {
        record(actorType, actorId, action, target, null, outcome, detail);
    }

    /**
     * 记一条审计（<b>核心签名</b>：显式主体类型 + 案件号）。
     *
     * <p>案件号来自 {@code moderation_review_queue.id}（即告知里的「案件 #N」）。
     * 与审核无关的动作传 {@code null}，不要用 0 冒充——那会让「无案件」与「案件 0」混淆。
     *
     * @param actorType 主体类型
     * @param actorId   主体 id：命令路径为发起者，审核路径为<b>被处置者</b>；纯系统动作为 {@code null}
     * @param caseId    关联案件号；无则 {@code null}
     */
    @Transactional
    public void record(ActorType actorType, Long actorId, String action, Long target, Long caseId,
                       AuditEntry.Outcome outcome, String detail) {
        try {
            repository.save(new AuditEntry(actorType, actorId, action, target, caseId, outcome, detail,
                    clock.instant()));
        } catch (RuntimeException ex) {
            // 审计降级：不中断业务，但必须留下可追查的痕迹（此处无法落库，只能进日志）
            log.error("审计写入失败，已降级为日志（业务未中断）：action={} actorType={} actor={}",
                    action, actorType, actorId, ex);
        }
    }
}

package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.common.exception.TggException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 联邦申诉处理（模块八）。
 *
 * <p>流程：用户提交 → 入待审队列 → 联邦管理员裁定。
 *
 * <p><b>本阶段裁定的范围</b>：用户批注"封禁/解封裁决应为投票/多签，属模块十二，本阶段不做"。
 * 故此处只做**恢复性动作的裁定**（解封），并明确标注——它不是"新增全域名封禁"的裁决，
 * 后者待模块十二的多签机制。
 */
public class FederationAppealService {

    private static final Logger log = LoggerFactory.getLogger(FederationAppealService.class);

    /** 申诉类型：请求解除联邦封禁。 */
    public static final String TYPE_FEDBAN_UNBAN = "FEDBAN_UNBAN";

    private final FederationAppealRepository repository;

    public FederationAppealService(FederationAppealRepository repository) {
        this.repository = repository;
    }

    /** 提交一条申诉。 */
    @Transactional
    public FederationAppeal submit(Long userId, String appealType, String text) {
        FederationAppeal appeal = new FederationAppeal(userId, appealType, text, Instant.now());
        FederationAppeal saved = repository.save(appeal);
        log.info("联邦申诉已提交：id={}", saved.getId());
        return saved;
    }

    /** 待审申诉列表。 */
    @Transactional(readOnly = true)
    public List<FederationAppeal> pending() {
        return repository.findByStatusOrderByCreatedAtAsc(FederationAppeal.Status.PENDING.name());
    }

    /**
     * 裁定：通过（解封）或驳回。
     *
     * @param operator 裁定人；<b>恰是申诉人本人时拒绝</b>（「不可自裁」，与复核队列同一条约束）
     * @throws com.tg.heyisheng.bot.common.exception.TggException 操作人正是该申诉的提交人
     */
    @Transactional
    public Optional<FederationAppeal> decide(Long appealId, boolean approve, Long operator) {
        Optional<FederationAppeal> found = repository.findById(appealId);
        found.ifPresent(appeal -> {
            requireNotSelfDecision(appeal, operator);
            appeal.decide(approve ? FederationAppeal.Status.APPROVED : FederationAppeal.Status.REJECTED);
            repository.save(appeal);
        });
        return found;
    }

    /**
     * 「不可自裁」：申诉人本人不得裁定自己的申诉。
     *
     * <p>规则写在<b>服务层</b>而不是 {@code /approve} 与 {@code /reject} 各自的预检——
     * 那是两条入口，写在服务里才是「谁调都绕不过」。与 {@code ModerationReviewDecisionService}、
     * {@code MerchantService} 同一约束（同一类缺陷在三个模块里出现过，故三处都对齐）。
     *
     * <p>{@code operator} 为 {@code null}（无操作人上下文的内部调用）时不拦——那种场景
     * 不存在「自己」；命令入口的 operator 恒非空（{@code FederationAdminGuard} 已验明身份）。
     */
    private static void requireNotSelfDecision(FederationAppeal appeal, Long operator) {
        if (operator != null && operator.equals(appeal.getUserId())) {
            throw new TggException("不能裁定自己的申诉：申诉 #" + appeal.getId()
                    + " 的提交人就是你。请让其他联邦管理员处理。");
        }
    }
}

package com.tg.heyisheng.bot.federation;

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

    /** 裁定：通过（解封）或驳回。 */
    @Transactional
    public Optional<FederationAppeal> decide(Long appealId, boolean approve) {
        Optional<FederationAppeal> found = repository.findById(appealId);
        found.ifPresent(appeal -> {
            appeal.decide(approve ? FederationAppeal.Status.APPROVED : FederationAppeal.Status.REJECTED);
            repository.save(appeal);
        });
        return found;
    }
}

package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 把中高风险命中写入人工复核队列。
 *
 * <p><b>隐私</b>：只落「判定结论 + 定位信息」，绝不落消息正文
 * （见 {@link ModerationReviewItem}——实体本身没有承载正文的字段）。
 *
 * <p><b>fail-open</b>：入队是<b>增强</b>而非<b>门禁</b>——数据库抖动不得让消息处理失败。
 * 失败记 ERROR 级日志：静默降级会造成「明明命中了却没有记录」且事后无从排查。
 *
 * <p>日志侧仍不含明文群/用户 id（与全项目日志脱敏口径一致）。
 */
@Service
public class ModerationReviewQueueService implements ModerationReviewRecorder {

    private static final Logger log = LoggerFactory.getLogger(ModerationReviewQueueService.class);

    private final ModerationReviewRepository repository;

    public ModerationReviewQueueService(ModerationReviewRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Optional<Long> record(UpdateContext ctx, ModerationVerdict verdict) {
        try {
            ModerationReviewItem item = new ModerationReviewItem(
                    ctx.chatId(),
                    ctx.userId(),
                    ctx.messageId().orElse(null),
                    verdict.matchedRuleIds(),
                    verdict.riskLevel(),
                    verdict.hardLine());
            ModerationReviewItem saved = repository.save(item);
            log.info("审核命中已入队待人工复核：rule={} level={}",
                    verdict.matchedRuleIds(), verdict.riskLevel());
            // 自增主键回填后即为**案件号**——上游据此在告知里给出编号、并允许当事人申诉。
            // 理论上 save 后必有 id；真为 null 时返回空（调用方降级为不带编号的告知），
            // 绝不把 null 当编号渲染出去。
            return Optional.ofNullable(saved.getId());
        } catch (RuntimeException ex) {
            log.error("写入复核队列失败（rule={}），已跳过——不得因 DB 故障中断消息处理",
                    verdict.matchedRuleIds(), ex);
            // 入队失败 → 无案件号。调用方据此降级，不能因此中断消息处理。
            return Optional.empty();
        }
    }
}

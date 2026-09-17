package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    public void record(UpdateContext ctx, ModerationVerdict verdict) {
        try {
            ModerationReviewItem item = new ModerationReviewItem(
                    ctx.chatId(),
                    ctx.userId(),
                    ctx.messageId().orElse(null),
                    verdict.matchedRuleIds(),
                    verdict.riskLevel());
            repository.save(item);
            log.info("审核命中已入队待人工复核：rule={} level={}",
                    verdict.matchedRuleIds(), verdict.riskLevel());
        } catch (RuntimeException ex) {
            log.error("写入复核队列失败（rule={}），已跳过——不得因 DB 故障中断消息处理",
                    verdict.matchedRuleIds(), ex);
        }
    }
}

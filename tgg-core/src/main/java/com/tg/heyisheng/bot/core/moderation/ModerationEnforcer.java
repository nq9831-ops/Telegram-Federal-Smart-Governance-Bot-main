package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;

import java.util.Optional;

/**
 * 把审核判定变成实际处置。
 *
 * <p><b>存在理由</b>：判定结果挂到上下文后若无消费者，审核在功能上等于零——
 * 这正是本项目反复出现的「接好了但没通电」模式（RBAC 与功能开关都栽过）。
 *
 * <p><b>当前处置范围（v1）</b>：命中即删除消息。这是 V5.0 分级处置的公共前提
 * （轻度「删除+警告」与重度「删除+禁言」都含删除）；警告文案与禁言、
 * 扣分、硬红线冻结属后续增量，本类留有扩展位。
 *
 * <p><b>只返回一个方法</b>：webhook 模式下库把 handler 返回值作为 HTTP 响应体交回 Telegram 执行，
 * 一次只能执行一个 Bot API 方法。因此这里优先返回<b>删除</b>——
 * 它是唯一能立刻止损的动作；附带通知（警告文案）需另走发送通道，属后续设计。
 */
public class ModerationEnforcer {

    private static final Logger log = LoggerFactory.getLogger(ModerationEnforcer.class);

    /**
     * 依据上下文中的判定结果决定是否处置。
     *
     * @return 需要执行的 Bot API 方法；无需处置时为空
     */
    public Optional<BotApiMethod<?>> enforce(UpdateContext ctx) {
        if (ctx == null) {
            return Optional.empty();
        }

        Optional<ModerationVerdict> verdictOpt = ctx.find(ModerationVerdict.class);
        if (verdictOpt.isEmpty() || !verdictOpt.get().needsReview()) {
            return Optional.empty();
        }

        ModerationVerdict verdict = verdictOpt.get();

        // 没有消息 id 就无法定位目标（例如服务类更新）——记日志而不是静默跳过，
        // 否则会表现为「审核命中了但没有处置」且查不出原因。
        if (ctx.messageId().isEmpty() || ctx.chatId() == null) {
            log.warn("审核命中但缺少处置所需的 chatId/messageId，无法删除：rule={}",
                    verdict.matchedRuleIds());
            return Optional.empty();
        }

        if (verdict.shouldFreezeImmediately()) {
            // 硬红线：立即处置、不等复核。冻结（禁言/封禁）属后续增量，此处先保证删除生效。
            log.warn("命中硬性红线，立即删除（冻结动作待实现）：rule={}", verdict.matchedRuleIds());
        } else {
            log.info("命中审核规则，删除消息：rule={} level={}",
                    verdict.matchedRuleIds(), verdict.riskLevel());
        }

        return Optional.of(new DeleteMessage(String.valueOf(ctx.chatId()), ctx.messageId().orElseThrow()));
    }
}

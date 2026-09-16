package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;

import java.util.Optional;

/**
 * 把审核判定变成实际处置。
 *
 * <p><b>存在理由</b>：判定结果挂到上下文后若无消费者，审核在功能上等于零——
 * 这正是本项目反复出现的「接好了但没通电」模式（RBAC 与功能开关都栽过）。
 *
 * <p><b>处置范围</b>：命中即删除消息；命中<b>硬红线</b>时额外封禁发布者
 * （删除 + 封禁两个动作，见下）。删除是 V5.0 分级处置的公共前提
 * （轻度「删除+警告」与重度「删除+禁言」都含删除）；警告文案、禁言、
 * 扣分、中高风险人工复核属后续增量。
 *
 * <p><b>为什么删除作返回值、封禁走主动通道</b>：webhook 模式下库把 handler 返回值
 * 作为 HTTP 响应体交回 Telegram 执行，一次只能执行<b>一个</b>方法。删除是唯一能立刻
 * 止损的动作，故作为返回值保底；封禁等额外动作经 {@link ModerationActionSender} 主动调用。
 * 附带通知（警告文案）同样需走该通道，属后续设计。
 */
public class ModerationEnforcer {

    private static final Logger log = LoggerFactory.getLogger(ModerationEnforcer.class);

    /** 主动处置通道（封禁等）；默认空实现，装配层注入真实通道。 */
    private final ModerationActionSender actionSender;

    /** 空通道构造器：仅删除、不封禁（供未装配场景与单测兜底）。 */
    public ModerationEnforcer() {
        this(ModerationActionSender.noop());
    }

    /**
     * @param actionSender 主动处置通道；硬红线封禁等额外动作经它执行
     */
    public ModerationEnforcer(ModerationActionSender actionSender) {
        this.actionSender = actionSender == null ? ModerationActionSender.noop() : actionSender;
    }

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

        // 硬红线：立即冻结、不等复核。webhook 一次只能返回一个方法，故封禁走主动通道，
        // 删除作为返回值保底——两者独立，缺 messageId 时封禁仍应尝试。
        if (verdict.shouldFreezeImmediately()) {
            freezePublisher(ctx, verdict);
        }

        // 没有消息 id 就无法定位目标（例如服务类更新）——记日志而不是静默跳过，
        // 否则会表现为「审核命中了但没有处置」且查不出原因。
        if (ctx.messageId().isEmpty() || ctx.chatId() == null) {
            log.warn("审核命中但缺少处置所需的 chatId/messageId，无法删除：rule={}",
                    verdict.matchedRuleIds());
            return Optional.empty();
        }

        if (verdict.shouldFreezeImmediately()) {
            log.warn("命中硬性红线：删除消息并封禁发布者：rule={}", verdict.matchedRuleIds());
        } else {
            log.info("命中审核规则，删除消息：rule={} level={}",
                    verdict.matchedRuleIds(), verdict.riskLevel());
        }

        return Optional.of(new DeleteMessage(String.valueOf(ctx.chatId()), ctx.messageId().orElseThrow()));
    }

    /**
     * 封禁硬红线发布者。
     *
     * <p>需要 {@code chatId} 与 {@code userId} 才能定位目标；二者缺一即无法封禁——
     * 记日志后放弃，但<b>不影响删除</b>（删除由返回值保底，不依赖本方法成功）。
     */
    private void freezePublisher(UpdateContext ctx, ModerationVerdict verdict) {
        if (ctx.chatId() == null || ctx.userId() == null) {
            log.warn("命中硬性红线但缺少 chatId/userId，无法封禁发布者（仅删除）：rule={}",
                    verdict.matchedRuleIds());
            return;
        }
        actionSender.send(BanChatMember.builder()
                .chatId(ctx.chatId())
                .userId(ctx.userId())
                .build());
    }
}

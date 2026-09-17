package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * {@code /review_approve <编号> [备注]} —— <b>维持</b>复核结论（确认违规）。需为复核人。
 *
 * <p>语义见 {@link ModerationReviewDecisionService}：确认违规并按等级追加处置（HIGH 禁言 24h）。
 * 幂等——重复维持同一条只返回既有结论，不重复处置。
 */
@BotCommand(value = "review_approve", description = "维持复核结论（确认违规，复核人）")
public class ReviewApproveCommandHandler implements CommandHandler {

    static final String USAGE = "用法：/review_approve <编号> [备注]";

    private final ModerationReviewDecisionService decisions;
    private final ModerationReviewGuard guard;

    public ReviewApproveCommandHandler(ModerationReviewDecisionService decisions,
                                       ModerationReviewGuard guard) {
        this.decisions = decisions;
        this.guard = guard;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        return ReviewDecisionCommandSupport.handle(ctx, guard, decisions, ReviewStatus.APPROVED, USAGE);
    }

    static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

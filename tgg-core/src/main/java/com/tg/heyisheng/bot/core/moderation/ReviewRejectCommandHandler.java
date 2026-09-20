package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.Confirm;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * {@code /review_reject <编号> [备注]} —— <b>推翻</b>复核结论（判定误报）。需为复核人。
 *
 * <p>语义见 {@link ModerationReviewDecisionService}：记误报；若原判为硬红线（自动封禁）则<b>解封</b>
 * ——这是「操作员推翻权」最有分量的一半。幂等——重复推翻同一条只返回既有结论，不重复解封。
 */
@BotCommand(value = "review_reject", description = "推翻复核结论（判定误报，复核人）",
        confirm = Confirm.ALWAYS)
public class ReviewRejectCommandHandler implements CommandHandler {

    static final String USAGE = "用法：/review_reject <编号> [备注]\n备注是裁决理由，请勿粘贴消息正文（备注会落库）。";

    private final ModerationReviewDecisionService decisions;
    private final ModerationReviewGuard guard;

    public ReviewRejectCommandHandler(ModerationReviewDecisionService decisions,
                                      ModerationReviewGuard guard) {
        this.decisions = decisions;
        this.guard = guard;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        return ReviewDecisionCommandSupport.handle(ctx, guard, decisions, ReviewStatus.REJECTED, USAGE);
    }

    static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

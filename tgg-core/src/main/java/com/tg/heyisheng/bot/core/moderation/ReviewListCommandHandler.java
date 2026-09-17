package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;

/**
 * {@code /review_list} —— 列出<b>待人工复核</b>的审核命中（模块九 §10.4）。需为复核人。
 *
 * <p><b>权限载体是全局白名单，不是 {@code @BotCommand.requiredPermission}</b>：
 * 复核是<b>平台层</b>动作，而 {@code Permission}/{@code Role} 是群内权能模型
 * ——理由与 {@code MerchantReviewCommandHandler} / {@code FederationAdminGuard} 一致。
 * 故本命令不声明权限点，门控在 handler 内完成。
 *
 * <p><b>非复核人返回 {@code null}（静默）</b>：与命令分发器的「权限不足即静默」语义一致
 * ——回复「权限不足」等于向无权者确认了命令存在。
 *
 * <p><b>不含正文</b>：列表只展示判定结论（规则 id / 等级 / 定位），正文由 scrub 清除、从不入库。
 */
@BotCommand(value = "review_list", description = "列出待复核的审核命中（复核人）")
public class ReviewListCommandHandler implements CommandHandler {

    /** Telegram 单条消息硬上限（超长整条发送失败）。 */
    private static final int TELEGRAM_TEXT_LIMIT = 4096;
    /** 为「已截断」提示预留的余量。 */
    private static final int TRUNCATION_RESERVE = 40;

    private final ModerationReviewDecisionService decisions;
    private final ModerationReviewGuard guard;

    public ReviewListCommandHandler(ModerationReviewDecisionService decisions,
                                    ModerationReviewGuard guard) {
        this.decisions = decisions;
        this.guard = guard;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        if (!guard.isReviewer(ctx.userId())) {
            return null;
        }
        List<ModerationReviewItem> pending = decisions.listPending();
        if (pending.isEmpty()) {
            return reply(ctx, "当前没有待复核的审核命中。");
        }

        StringBuilder sb = new StringBuilder("待复核（共 ").append(pending.size()).append(" 条）：\n");
        for (ModerationReviewItem item : pending) {
            String line = "#" + item.getId()
                    + " · 群 " + item.getChatId()
                    + " · 规则 " + item.getRuleIds()
                    + " · " + item.getRiskLevel()
                    + (item.isHardLine() ? " · 硬红线(已封禁)" : "")
                    + "\n";
            if (sb.length() + line.length() > TELEGRAM_TEXT_LIMIT - TRUNCATION_RESERVE) {
                sb.append("…（已截断，请先处理列表中的条目）\n");
                break;
            }
            sb.append(line);
        }
        return reply(ctx, sb.toString());
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

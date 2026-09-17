package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * {@code /review_approve} 与 {@code /review_reject} 的共用流程：<b>门控 → 解析 → 裁决 → 回执</b>。
 *
 * <p>两个命令只在「结论」上不同（维持 / 推翻），其余逐字相同——抽出一处
 * 避免两处条件漂移（本项目在 {@code UpdateDispatcher} 的 {@code resolvableHandler} 上
 * 踩过「两处判定各写一遍会漂移」的坑）。
 *
 * <p><b>非复核人返回 {@code null}（静默）</b>：见各命令的 javadoc。
 *
 * <p><b>备注不含正文</b>：{@code note} 是操作员自己写的裁决理由，不是被判定消息的正文
 * ——但它会落库（{@code note} 列）与进日志，故命令的帮助文案须提示「勿粘贴原文」。
 */
final class ReviewDecisionCommandSupport {

    private ReviewDecisionCommandSupport() {
    }

    static BotApiMethod<?> handle(UpdateContext ctx,
                                  ModerationReviewGuard guard,
                                  ModerationReviewDecisionService decisions,
                                  ReviewStatus decision,
                                  String usage) {
        if (!guard.isReviewer(ctx.userId())) {
            return null;
        }

        String args = ctx.commandArgs().orElse(null);
        if (args == null || args.isBlank()) {
            return reply(ctx, usage);
        }
        String[] parts = args.trim().split("\\s+", 2);
        long id;
        try {
            id = Long.parseLong(parts[0]);
        } catch (NumberFormatException ex) {
            return reply(ctx, usage);
        }
        String note = parts.length > 1 ? parts[1].trim() : null;

        ModerationReviewDecisionService.Outcome outcome;
        try {
            outcome = decisions.decide(id, decision, ctx.userId(), note);
        } catch (TggException ex) {
            // 输入问题（如备注超长）：如实回显原因。不让异常穿透到分发层——那会变成静默失败，
            // 而「裁决没生效却不告诉操作员」比直接报错危险得多。
            return reply(ctx, "裁决未生效：" + ex.getMessage());
        }

        String verb = decision == ReviewStatus.APPROVED ? "维持" : "推翻";
        return switch (outcome.result()) {
            case NOT_FOUND -> reply(ctx, "未找到复核编号 " + id + "。");
            case ALREADY_DECIDED -> reply(ctx, "复核 #" + id + " 已是终态结论（" + outcome.status()
                    + "），本次未改动。");
            case DECIDED -> reply(ctx, "复核 #" + id + " 已" + verb + "：" + outcome.status()
                    + (decision == ReviewStatus.REJECTED && outcome.status() == ReviewStatus.REJECTED
                    ? "（若原判为硬红线，已触发解封）" : "。"));
        };
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

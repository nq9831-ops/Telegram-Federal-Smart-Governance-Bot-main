package com.tg.heyisheng.bot.core.groupconfig;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository;
import com.tg.heyisheng.bot.core.moderation.ReviewStatus;
import com.tg.heyisheng.bot.core.wordfilter.BannedWordService;
import com.tg.heyisheng.bot.core.wordfilter.TaughtRuleService;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * {@code /status} —— 一看便知「本群现在的治理状态」（原文 §2.1）。
 *
 * <p><b>为什么需要它</b>：管理员此前要回答「本群词表配了没、有没有积压的案件」只能靠翻命令，
 * 而普通成员完全无从判断「这个群到底有没有在管」。一屏概览比一长串命令更能建立预期。
 *
 * <p><b>只显示数量与状态，不显示内容</b>：违禁词**条数**不泄露词表（内容要 {@code /words}
 * 且需 MANAGE_CONFIG），教学规则同理。这是本命令能对全体成员开放的前提——
 * 一旦回显内容，它就变成了绕过权限的后门。
 *
 * <p><b>待复核数取「本群」而非全局</b>：本命令的回复对整个群可见，
 * 把平台级全局待办数播到群里等于泄露平台负载与他人案件量。故用
 * {@link ModerationReviewRepository#countByChatIdAndStatus}。
 *
 * <p><b>无条件装配</b>：基础命令，不挂 {@code tgg.interaction.enabled}——那是面板（{@code /menu}）
 * 与帮助（{@code /help}）的开关，而「这个群在不在管」是任何时候都该能问的问题。
 *
 * <p><b>{@code publicCommand = true}</b>：自助命令，对全体成员可见。
 * {@code worksWhenDisabled} 保持默认 {@code false}——它不是恢复类命令（恢复类是 {@code /enable}）。
 */
@BotCommand(value = "status", description = "查看本群的治理状态", publicCommand = true,
        category = MenuCategory.SELF_SERVICE)
public class StatusCommandHandler implements CommandHandler {

    static final String HEADER = "本群治理状态：";

    private final GroupConfigService groups;
    private final BannedWordService bannedWords;
    private final TaughtRuleService taughtRules;
    private final ModerationReviewRepository reviews;

    public StatusCommandHandler(GroupConfigService groups,
                                BannedWordService bannedWords,
                                TaughtRuleService taughtRules,
                                ModerationReviewRepository reviews) {
        this.groups = groups;
        this.bannedWords = bannedWords;
        this.taughtRules = taughtRules;
        this.reviews = reviews;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        Long chatId = ctx.chatId();
        if (chatId == null) {
            return SendMessage.builder().chatId(String.valueOf(ctx.chatId()))
                    .text("无法识别本群。").build();
        }
        boolean enabled = groups.findOrDefault(chatId).enabled();
        StringBuilder sb = new StringBuilder(HEADER);
        sb.append("\n治理状态：").append(enabled ? "已启用" : "已停用（发 /enable 可恢复）");
        sb.append("\n违禁词：").append(bannedWords.listWords(chatId).size()).append(" 条");
        sb.append("\n教学规则：").append(taughtRules.listTaught(chatId).size()).append(" 条");
        // 待复核只给「本群」的条数：回复全群可见，全局数属平台面信息。
        sb.append("\n待复核案件：").append(reviews.countByChatIdAndStatus(chatId, ReviewStatus.PENDING))
                .append(" 条");
        sb.append("\n\n看你能用哪些功能：/help");
        return SendMessage.builder().chatId(String.valueOf(chatId)).text(sb.toString()).build();
    }
}

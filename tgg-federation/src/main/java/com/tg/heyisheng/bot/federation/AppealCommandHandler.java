package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * {@code /appeal <申诉内容>}：任何成员可提交联邦申诉（照 Fedbot 的 /appeal）。
 *
 * <p>权限：不限（申诉是被处罚者的权利）。申诉正文取 {@code ctx.commandArgs()}——
 * 它是用户主动提交的管理输入，不进审核判定。
 */
@Component
@BotCommand(value = "appeal", description = "提交联邦申诉（解封申请）", publicCommand = true,
        category = MenuCategory.SELF_SERVICE)
@ConditionalOnProperty(prefix = "tgg.federation", name = "enabled", havingValue = "true")
public class AppealCommandHandler implements CommandHandler {

    private final FederationAppealService appealService;

    public AppealCommandHandler(FederationAppealService appealService) {
        this.appealService = appealService;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        String text = ctx.commandArgs().orElse(null);
        if (text == null) {
            return reply(ctx, FederationMessages.APPEAL_USAGE);
        }
        FederationAppeal appeal = appealService.submit(
                ctx.userId(), FederationAppealService.TYPE_FEDBAN_UNBAN, text);
        return reply(ctx, FederationMessages.appealSubmitted(appeal.getId()));
    }

    static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

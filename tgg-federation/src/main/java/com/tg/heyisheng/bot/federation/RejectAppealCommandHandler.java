package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.Confirm;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;

import java.util.Optional;

/** {@code /reject <id>}：驳回申诉（仅联邦管理员）。 */
@Component
@BotCommand(value = "reject", description = "驳回联邦申诉（联邦管理员）",
        confirm = Confirm.ALWAYS)
@ConditionalOnProperty(prefix = "tgg.federation", name = "enabled", havingValue = "true")
public class RejectAppealCommandHandler implements CommandHandler {

    private final FederationAppealService appealService;
    private final FederationAdminGuard adminGuard;

    public RejectAppealCommandHandler(FederationAppealService appealService,
                                      FederationAdminGuard adminGuard) {
        this.appealService = appealService;
        this.adminGuard = adminGuard;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        if (!adminGuard.isAdmin(ctx.userId())) {
            return null;
        }
        String arg = ctx.commandArgs().orElse(null);
        Long id = null;
        if (arg != null) {
            try {
                id = Long.parseLong(arg.trim());
            } catch (NumberFormatException ignored) {
                id = null;
            }
        }
        if (id == null) {
            return AppealCommandHandler.reply(ctx, "用法：/reject <申诉编号>");
        }
        Optional<FederationAppeal> decided = appealService.decide(id, false);
        return AppealCommandHandler.reply(ctx, decided.isPresent()
                ? "申诉 #" + id + " 已驳回。"
                : "未找到申诉 #" + id + "。");
    }
}

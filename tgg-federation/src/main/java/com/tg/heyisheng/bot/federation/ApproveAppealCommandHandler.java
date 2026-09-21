package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.Confirm;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;

import java.util.Optional;

/**
 * {@code /approve <id>}：通过申诉（仅联邦管理员）。
 *
 * <p><b>这是"恢复性动作"（解封），不是"新增封禁"的裁决</b>——后者按用户批注属模块十二的多签机制。
 * 解封的风险方向与封禁相反，故本阶段允许管理员直接执行。
 */
@Component
@BotCommand(value = "approve", description = "通过联邦申诉（联邦管理员）",
        confirm = Confirm.ALWAYS, category = MenuCategory.REVIEW)
@ConditionalOnProperty(prefix = "tgg.federation", name = "enabled", havingValue = "true")
public class ApproveAppealCommandHandler implements CommandHandler {

    private final FederationAppealService appealService;
    private final FederationAdminGuard adminGuard;

    public ApproveAppealCommandHandler(FederationAppealService appealService,
                                       FederationAdminGuard adminGuard) {
        this.appealService = appealService;
        this.adminGuard = adminGuard;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        if (!adminGuard.isAdmin(ctx.userId())) {
            return null;
        }
        Long id = parseId(ctx);
        if (id == null) {
            return AppealCommandHandler.reply(ctx, FederationMessages.APPROVE_USAGE);
        }
        Optional<FederationAppeal> decided;
        try {
            decided = appealService.decide(id, true, ctx.userId());
        } catch (TggException ex) {
            // 业务拒绝（如「不能裁定自己的申诉」）：如实回显，不让它变成静默失败
            return AppealCommandHandler.reply(ctx, ex.getMessage());
        }
        return AppealCommandHandler.reply(ctx, decided.isPresent()
                ? FederationMessages.approved(id)
                : FederationMessages.notFound(id));
    }

    private static Long parseId(UpdateContext ctx) {
        String arg = ctx.commandArgs().orElse(null);
        if (arg == null) {
            return null;
        }
        try {
            return Long.parseLong(arg.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

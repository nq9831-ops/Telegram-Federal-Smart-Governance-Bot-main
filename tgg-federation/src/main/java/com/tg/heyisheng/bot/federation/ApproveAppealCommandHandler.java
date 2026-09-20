package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.Confirm;
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
        confirm = Confirm.ALWAYS)
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
            return AppealCommandHandler.reply(ctx, "用法：/approve <申诉编号>");
        }
        Optional<FederationAppeal> decided = appealService.decide(id, true);
        return AppealCommandHandler.reply(ctx, decided.isPresent()
                ? "申诉 #" + id + " 已通过。"
                : "未找到申诉 #" + id + "。");
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

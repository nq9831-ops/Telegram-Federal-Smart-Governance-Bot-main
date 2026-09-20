package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;

import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code /pending}：列出待审联邦申诉（仅联邦管理员）。
 *
 * <p><b>权限判定为何在 handler 内</b>：联邦管理员是**全局**概念，而 {@code CommandDispatcher}
 * 的权限门控走群内 {@code PermissionChecker}（{@code Role} 带 chatId 语义）——群内模型装不下全局角色。
 * 故此处用 {@link FederationAdminGuard} 自查，门控仍在命令层，只是授权源不同。
 * 权限不足时静默忽略（与 dispatcher 的既有行为一致，不暴露命令是否存在）。
 */
@Component
@BotCommand(value = "pending", description = "查看待审联邦申诉（联邦管理员）",
        category = MenuCategory.REVIEW)
@ConditionalOnProperty(prefix = "tgg.federation", name = "enabled", havingValue = "true")
public class PendingAppealsCommandHandler implements CommandHandler {

    private final FederationAppealService appealService;
    private final FederationAdminGuard adminGuard;

    public PendingAppealsCommandHandler(FederationAppealService appealService,
                                        FederationAdminGuard adminGuard) {
        this.appealService = appealService;
        this.adminGuard = adminGuard;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        if (!adminGuard.isAdmin(ctx.userId())) {
            return null;
        }
        List<FederationAppeal> pending = appealService.pending();
        if (pending.isEmpty()) {
            return AppealCommandHandler.reply(ctx, "当前没有待审申诉。");
        }
        String body = pending.stream()
                .map(a -> "#" + a.getId() + " [" + a.getAppealType() + "]")
                .collect(Collectors.joining("\n"));
        return AppealCommandHandler.reply(ctx, "待审申诉（共 " + pending.size() + " 条）：\n" + body);
    }
}

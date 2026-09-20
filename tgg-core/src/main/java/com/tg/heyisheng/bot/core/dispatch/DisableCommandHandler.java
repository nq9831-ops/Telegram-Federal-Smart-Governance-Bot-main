package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.core.permission.Permission;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * {@code /disable} —— 关闭本群的自动化能力。需 {@link Permission#MANAGE_CONFIG}。
 *
 * <p>关闭后，本群不再执行<b>非恢复类</b>命令（开关判断在 {@code CommandDispatcher}，
 * 中间件只负责加载配置）。{@code /enable} 声明了 {@code worksWhenDisabled = true}，
 * 因此关闭状态下仍可执行——这是必须的，否则该群会永久锁死。
 */
@BotCommand(value = "disable", description = "关闭本群自动化能力（需管理员权限）",
        requiredPermission = Permission.MANAGE_CONFIG, confirm = Confirm.ALWAYS)
@Component
public class DisableCommandHandler implements CommandHandler {

    static final String REPLY = "已关闭本群的自动化能力。";

    private final GroupConfigService groupConfigService;

    public DisableCommandHandler(GroupConfigService groupConfigService) {
        this.groupConfigService = groupConfigService;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        groupConfigService.setEnabled(ctx.chatId(), false);
        return new SendMessage(String.valueOf(ctx.chatId()), REPLY);
    }
}

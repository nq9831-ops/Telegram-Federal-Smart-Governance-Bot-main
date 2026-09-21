package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.core.permission.Permission;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * {@code /enable} —— 开启本群的自动化能力。需 {@link Permission#MANAGE_CONFIG}。
 *
 * <p>本命令的存在意义：在此之前 {@code GroupConfigService.setEnabled} 没有任何生产调用方，
 * 功能开关在生产中<b>无法被写入</b>，中间件里的开关门控永远不可达——
 * 即"接好了但没通电"。有了读写入口，该特性才真正可用。
 *
 * <p><b>worksWhenDisabled = true 不可省略</b>：本命令是「恢复类」命令。
 * 早期实现把开关判断放在中间件里直接中断链，导致 {@code /disable} 之后
 * 连 {@code /enable} 都进不来，该群<b>永久锁死</b>（实测暴露）。
 * 现在开关判断在命令级，并由本属性为恢复类命令开豁免。
 *
 * <p>刻意做成无参数命令（而非 {@code /config on}）：{@link UpdateContext} 不携带消息正文
 * （隐私约束），因此命令参数不可用；无参命令完全避开这一点。
 */
@BotCommand(value = "enable", description = "开启本群自动化能力（需管理员权限）",
        requiredPermission = Permission.MANAGE_CONFIG,
        worksWhenDisabled = true,
        category = MenuCategory.GROUP)
@Component
public class EnableCommandHandler implements CommandHandler {

    static final String REPLY = DispatchMessages.ENABLE_REPLY;

    private final GroupConfigService groupConfigService;

    public EnableCommandHandler(GroupConfigService groupConfigService) {
        this.groupConfigService = groupConfigService;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        groupConfigService.setEnabled(ctx.chatId(), true);
        return new SendMessage(String.valueOf(ctx.chatId()), REPLY);
    }
}

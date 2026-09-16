package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.exception.TggDispatchException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigView;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;

import java.util.Optional;

/**
 * 命令分发器：按上下文中的命令路由到对应处理器，并在路由前做权限门控。
 *
 * <p>命令文本由 {@code UpdateDispatcher} 在构造上下文时用库的 {@code Message.getCommand()}
 * 提取（基于 Telegram 服务端标注的 {@code MessageEntity}(bot_command)）——因此
 * {@code /echo@MyBot} 等形式都能正确归一化。
 *
 * <p><b>权限不足时的行为是「静默忽略」</b>：不回复、不报错。
 * 这样不会向无权用户暴露命令是否存在（回复"权限不足"等于确认了命令存在）。
 */
public class CommandDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CommandDispatcher.class);

    private final CommandRegistry registry;
    private final PermissionChecker permissionChecker;

    /** 默认用「全部按最小权限处理」的判定器——未装配权限源时不放行任何受限命令。 */
    public CommandDispatcher(CommandRegistry registry) {
        this(registry, new PermissionChecker((chatId, userId) -> Role.MEMBER));
    }

    public CommandDispatcher(CommandRegistry registry, PermissionChecker permissionChecker) {
        this.registry = registry;
        this.permissionChecker = permissionChecker;
    }

    /**
     * 解析并路由命令。
     *
     * <p><b>只接收 {@link UpdateContext}</b>，不再从 Update 重新构造上下文——
     * 否则中间件链 enrich 到上下文里的信息传不到 handler。
     *
     * @return 处理器产出的 Bot API 调用；无命令、未知命令、权限不足或处理器返回 null 时为空
     */
    public Optional<BotApiMethod<?>> dispatch(UpdateContext ctx) throws Exception {
        if (ctx == null || !ctx.hasCommand()) {
            return Optional.empty();
        }

        String command = ctx.command().orElseThrow();
        Optional<CommandHandler> handler = registry.find(command);
        if (handler.isEmpty()) {
            return Optional.empty();
        }

        Permission required = registry.requiredPermission(command);
        if (!permissionChecker.has(ctx.chatId(), ctx.userId(), required)) {
            log.debug("权限不足（需要 {}），忽略命令 {}", required, command);
            return Optional.empty();
        }

        // 群功能开关：关闭时只放行「恢复类」命令。
        // 若在此处一刀切地拒绝，被停用的群连 /enable 都进不来——会永久锁死。
        if (!registry.worksWhenDisabled(command) && !isGroupEnabled(ctx)) {
            log.debug("群 {} 功能已关闭，忽略命令 {}", ctx.chatId(), command);
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(handler.get().handle(ctx));
        } catch (Exception ex) {
            // 统一包装为项目异常，便于上游 @RestControllerAdvice 识别与记录
            throw new TggDispatchException("命令处理失败：" + command, ex);
        }
    }

    /**
     * 读取上下文里由 {@code GroupConfigMiddleware} 挂载的开关状态。
     *
     * <p>未挂载时按「启用」处理——这保证不装配该中间件的场景（如单元测试）行为不变。
     */
    private static boolean isGroupEnabled(UpdateContext ctx) {
        return ctx.find(GroupConfigView.class).map(GroupConfigView::enabled).orElse(true);
    }
}

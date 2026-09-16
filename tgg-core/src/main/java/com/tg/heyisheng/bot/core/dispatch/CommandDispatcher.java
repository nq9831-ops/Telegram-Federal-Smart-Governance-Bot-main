package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.exception.TggDispatchException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;

import java.util.Optional;

/**
 * 命令分发器：按上下文中的命令路由到对应处理器。
 *
 * <p>命令文本由 {@code UpdateDispatcher} 在构造上下文时用库的 {@code Message.getCommand()}
 * 提取（基于 Telegram 服务端标注的 {@code MessageEntity}(bot_command)，
 * 而非文本首 token 切分）——因此 {@code /echo@MyBot} 等形式都能正确归一化。
 */
public class CommandDispatcher {

    private final CommandRegistry registry;

    public CommandDispatcher(CommandRegistry registry) {
        this.registry = registry;
    }

    /**
     * 解析并路由命令。
     *
     * <p><b>只接收 {@link UpdateContext}</b>，不再从 Update 重新构造上下文——
     * 否则中间件链 enrich 到上下文里的信息传不到 handler（链上的是一个对象、
     * handler 收到的是另一个），这是个会被后续切片放大的结构性缺陷。
     *
     * @return 处理器产出的 Bot API 调用；无命令、未知命令或处理器返回 null 时为空
     */
    public Optional<BotApiMethod<?>> dispatch(UpdateContext ctx) throws Exception {
        if (ctx == null || !ctx.hasCommand()) {
            return Optional.empty();
        }

        Optional<CommandHandler> handler = registry.find(ctx.command().orElseThrow());
        if (handler.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(handler.get().handle(ctx));
        } catch (Exception ex) {
            // 统一包装为项目异常，便于上游 @RestControllerAdvice 识别与记录
            throw new TggDispatchException("命令处理失败：" + ctx.command().orElse("(unknown)"), ex);
        }
    }
}

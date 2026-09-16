package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.middleware.MiddlewareChain;
import com.tg.heyisheng.bot.core.privacy.MessageScrubber;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.Optional;

/**
 * 更新分发主入口：库的 updateHandler 指向本类。
 *
 * <p>职责串接：构造 {@link UpdateContext}（<b>只带路由元数据，不带消息正文</b>）
 * → 跑中间件链 → 未中断则交给 {@link CommandDispatcher}。
 *
 * <p>链被中断（如限流、认证失败）时返回空，表示「已消费但不回复」。
 */
public class UpdateDispatcher {

    private final MiddlewareChain middlewareChain;
    private final CommandDispatcher commandDispatcher;
    private final MessageScrubber scrubber;

    public UpdateDispatcher(MiddlewareChain middlewareChain, CommandDispatcher commandDispatcher) {
        this(middlewareChain, commandDispatcher, new MessageScrubber());
    }

    public UpdateDispatcher(MiddlewareChain middlewareChain,
                            CommandDispatcher commandDispatcher,
                            MessageScrubber scrubber) {
        this.middlewareChain = middlewareChain;
        this.commandDispatcher = commandDispatcher;
        this.scrubber = scrubber;
    }

    public Optional<BotApiMethod<?>> dispatch(Update update) throws Exception {
        try {
            if (update == null) {
                return Optional.empty();
            }

            UpdateContext ctx = toContext(update);

            if (!middlewareChain.proceed(ctx)) {
                return Optional.empty();
            }

            return commandDispatcher.dispatch(ctx);
        } finally {
            // 隐私管道：无论成功、被中断还是抛异常，正文都必须被清除。
            // 放在 finally 里是刻意的——异常路径才是最容易被日志带出正文的那条。
            scrubber.scrub(update == null ? null : update.getMessage());
        }
    }

    /**
     * 从库的 Update 提取路由元数据。
     *
     * <p>刻意不复制 {@code message.getText()} / {@code caption} 等正文字段——
     * 这是切片 3「消息原文零存储」的前置约束（见设计文档 §5.6 与 UpdateContext javadoc）。
     * 命令名取自库的 {@code Message.getCommand()}（基于 MessageEntity，而非文本切分）。
     */
    static UpdateContext toContext(Update update) {
        Message message = update.getMessage();
        if (message == null) {
            return new UpdateContext(update.getUpdateId(), null, null, null);
        }
        Long userId = message.getFrom() == null ? null : message.getFrom().getId();
        Long chatId = message.getChat() == null ? null : message.getChat().getId();
        return new UpdateContext(update.getUpdateId(), userId, chatId, message.getCommand());
    }
}

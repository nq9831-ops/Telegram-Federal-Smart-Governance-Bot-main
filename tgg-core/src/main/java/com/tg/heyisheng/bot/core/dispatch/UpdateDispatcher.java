package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.middleware.MiddlewareChain;
import com.tg.heyisheng.bot.core.moderation.ModerationLayer;
import com.tg.heyisheng.bot.core.moderation.ModerationVerdict;
import com.tg.heyisheng.bot.core.privacy.MessageScrubber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.List;
import java.util.Optional;

/**
 * 更新分发主入口：库的 updateHandler 指向本类。
 *
 * <p>职责串接：审核消息内容 → 构造 {@link UpdateContext}（<b>只带路由元数据与判定结果，不带正文</b>）
 * → 跑中间件链 → 未中断则交给 {@link CommandDispatcher}。
 *
 * <p><b>审核与隐私的次序是关键</b>：审核必须在 {@code finally} 里的 scrub <b>之前</b>执行——
 * 那是正文仍在内存中的唯一时机；审核产出的是 {@link ModerationVerdict}（不含原文），
 * 因此可以安全地挂到上下文并进入后续链路。
 */
public class UpdateDispatcher {

    private static final Logger log = LoggerFactory.getLogger(UpdateDispatcher.class);

    private final MiddlewareChain middlewareChain;
    private final CommandDispatcher commandDispatcher;
    private final MessageScrubber scrubber;
    private final ModerationLayer moderationLayer;

    public UpdateDispatcher(MiddlewareChain middlewareChain, CommandDispatcher commandDispatcher) {
        this(middlewareChain, commandDispatcher, new MessageScrubber(), null);
    }

    public UpdateDispatcher(MiddlewareChain middlewareChain,
                            CommandDispatcher commandDispatcher,
                            MessageScrubber scrubber) {
        this(middlewareChain, commandDispatcher, scrubber, null);
    }

    /**
     * @param moderationLayer 审核层；为 null 表示不启用审核（此时不会挂载判定结果）
     */
    public UpdateDispatcher(MiddlewareChain middlewareChain,
                            CommandDispatcher commandDispatcher,
                            MessageScrubber scrubber,
                            ModerationLayer moderationLayer) {
        this.middlewareChain = middlewareChain;
        this.commandDispatcher = commandDispatcher;
        this.scrubber = scrubber;
        this.moderationLayer = moderationLayer;
    }

    public Optional<BotApiMethod<?>> dispatch(Update update) throws Exception {
        try {
            if (update == null) {
                return Optional.empty();
            }

            UpdateContext ctx = toContext(update);
            moderateInto(ctx, update);

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
     * 执行审核并把<b>判定结果</b>（不含原文）挂到上下文。
     *
     * <p>未命中时也挂载 {@link ModerationVerdict#clean()}——这样下游能区分
     * 「审过且干净」与「压根没审」，避免把"没审核"误当成"审核通过"。
     */
    private void moderateInto(UpdateContext ctx, Update update) {
        if (moderationLayer == null) {
            return;
        }
        Message message = update.getMessage();
        if (message == null) {
            return;
        }

        ModerationVerdict verdict = moderationLayer.inspect(message.getText())
                .map(hit -> new ModerationVerdict(hit.riskLevel(), hit.hardLine(), List.of(hit.ruleId())))
                .orElseGet(ModerationVerdict::clean);

        ctx.attach(verdict);

        if (verdict.needsReview()) {
            // 审计日志：只记规则 id 与等级，绝不记正文或命中片段——
            // 否则消息原文会经日志这条侧路泄露，绕过 scrub。
            log.info("L1 审核命中：rule={} level={} hardLine={} chatId={}",
                    verdict.matchedRuleIds(), verdict.riskLevel(), verdict.hardLine(), ctx.chatId());
        }
    }

    /**
     * 从库的 Update 提取路由元数据。
     *
     * <p>刻意不复制 {@code message.getText()} / {@code caption} 等正文字段——
     * 这是「消息原文零存储」的前置约束。
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

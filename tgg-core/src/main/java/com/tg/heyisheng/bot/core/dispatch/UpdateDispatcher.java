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
 *
 * <p><b>正文口径必须与 {@link MessageScrubber} 一致</b>：清除端把 {@code text} 与 {@code caption}
 * 都视为正文，审核端也必须两者都看——否则带 caption 的图片消息会因 {@code getText()} 为 null
 * 而被判成「审过且干净」（假 clean），那比「没审」更危险。
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

            Message message = relevantMessage(update);
            UpdateContext ctx = toContext(update, message);
            moderateInto(ctx, message);

            if (!middlewareChain.proceed(ctx)) {
                return Optional.empty();
            }

            return commandDispatcher.dispatch(ctx);
        } finally {
            // 隐私管道：无论成功、被中断还是抛异常，正文都必须被清除。
            // 放在 finally 里是刻意的——异常路径才是最容易被日志带出正文的那条。
            // 注意这里也走 relevantMessage：编辑消息与频道帖同样要清。
            scrubber.scrub(update == null ? null : relevantMessage(update));
        }
    }

    /**
     * 取出本次更新中「承载内容的那条消息」。
     *
     * <p>覆盖三类来源，缺一不可：
     * <ul>
     *   <li>{@code message} —— 普通新消息</li>
     *   <li>{@code edited_message} —— 编辑后的消息。<b>这是真实的绕过手法</b>：
     *       先发干净内容通过审核，再编辑成广告，若只审新消息就完全漏掉</li>
     *   <li>{@code channel_post} —— 频道帖（机器人在频道内时）</li>
     * </ul>
     */
    static Message relevantMessage(Update update) {
        if (update == null) {
            return null;
        }
        if (update.getMessage() != null) {
            return update.getMessage();
        }
        if (update.getEditedMessage() != null) {
            return update.getEditedMessage();
        }
        return update.getChannelPost();
    }

    /**
     * 执行审核并把<b>判定结果</b>（不含原文）挂到上下文。
     *
     * <p>未命中时也挂载 {@link ModerationVerdict#clean()}——这样下游能区分
     * 「审过且干净」与「压根没审」，避免把"没审核"误当成"审核通过"。
     */
    private void moderateInto(UpdateContext ctx, Message message) {
        if (moderationLayer == null || message == null) {
            return;
        }

        String content = contentOf(message);
        ModerationVerdict verdict = moderationLayer.inspect(content)
                .map(hit -> new ModerationVerdict(hit.riskLevel(), hit.hardLine(), List.of(hit.ruleId())))
                .orElseGet(ModerationVerdict::clean);

        ctx.attach(verdict);

        if (verdict.needsReview()) {
            // 审计日志：不记正文、不记命中片段（避免经日志这条侧路泄露原文）。
            // 记录规则 id 与群/用户以便追责，等级与红线标记供后续处置分级使用。
            log.info("L1 审核命中：rule={} level={} hardLine={} chatId={} userId={}",
                    verdict.matchedRuleIds(), verdict.riskLevel(), verdict.hardLine(),
                    ctx.chatId(), ctx.userId());
        }
    }

    /**
     * 拼出待审核的内容。
     *
     * <p>与 {@link MessageScrubber} 的口径保持一致：文本消息看 {@code text}，
     * 媒体消息看 {@code caption}，两者都有则拼接（图片带长文说明的情况真实存在）。
     */
    static String contentOf(Message message) {
        String text = message.getText();
        String caption = message.getCaption();

        boolean hasText = text != null && !text.isEmpty();
        boolean hasCaption = caption != null && !caption.isEmpty();

        if (hasText && hasCaption) {
            return text + "\n" + caption;
        }
        if (hasText) {
            return text;
        }
        return hasCaption ? caption : null;
    }

    /**
     * 从库的 Update 提取路由元数据。
     *
     * <p>刻意不复制正文字段——这是「消息原文零存储」的前置约束。
     * 命令名取自库的 {@code Message.getCommand()}（基于 MessageEntity，而非文本切分）。
     */
    static UpdateContext toContext(Update update, Message message) {
        if (message == null) {
            return new UpdateContext(update.getUpdateId(), null, null, null);
        }
        Long userId = message.getFrom() == null ? null : message.getFrom().getId();
        Long chatId = message.getChat() == null ? null : message.getChat().getId();
        return new UpdateContext(update.getUpdateId(), userId, chatId, message.getCommand());
    }
}

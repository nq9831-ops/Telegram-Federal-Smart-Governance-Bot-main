package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.admission.JoinVerificationService;
import com.tg.heyisheng.bot.core.callback.CallbackRouter;
import com.tg.heyisheng.bot.core.middleware.MiddlewareChain;
import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import com.tg.heyisheng.bot.core.moderation.ModerationEnforcer;
import com.tg.heyisheng.bot.core.moderation.ModerationLayer;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRecorder;
import com.tg.heyisheng.bot.core.moderation.ModerationVerdict;
import com.tg.heyisheng.bot.core.moderation.RepeatedMessageDetector;
import com.tg.heyisheng.bot.core.privacy.MessageScrubber;
import com.tg.heyisheng.bot.core.wordfilter.BannedWordDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Venue;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.polls.Poll;
import org.telegram.telegrambots.meta.api.objects.polls.PollOption;
import org.telegram.telegrambots.meta.api.objects.polls.PollOptionAdded;
import org.telegram.telegrambots.meta.api.objects.polls.PollOptionDeleted;

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
    private final IdHasher idHasher;
    private final ModerationEnforcer enforcer;
    private final ModerationReviewRecorder reviewRecorder;
    private final BannedWordDetector bannedWordDetector;
    private final RepeatedMessageDetector repeatedMessageDetector;
    private final CallbackRouter callbackRouter;
    private final JoinVerificationService joinVerificationService;

    /**
     * 构造器已增至 9 个参数，继续叠加会难以维护——新增装配一律走 {@link #builder()}；
     * 下列重载保留给既有测试与"只需要一部分能力"的场景。
     */
    public static Builder builder() {
        return new Builder();
    }

    /** 装配构建器：必填项为中间件链与命令分发器，其余按需设置。 */
    public static final class Builder {
        private MiddlewareChain middlewareChain;
        private CommandDispatcher commandDispatcher;
        private MessageScrubber scrubber = new MessageScrubber();
        private ModerationLayer moderationLayer;
        private IdHasher idHasher = IdHasher.fromEnvironment();
        private ModerationActionSender actionSender = ModerationActionSender.noop();
        private ModerationReviewRecorder reviewRecorder = ModerationReviewRecorder.noop();
        private BannedWordDetector bannedWordDetector;
        private RepeatedMessageDetector repeatedMessageDetector;
        private CallbackRouter callbackRouter;
        private JoinVerificationService joinVerificationService;

        public Builder middlewareChain(MiddlewareChain value) {
            this.middlewareChain = value;
            return this;
        }

        public Builder commandDispatcher(CommandDispatcher value) {
            this.commandDispatcher = value;
            return this;
        }

        public Builder scrubber(MessageScrubber value) {
            this.scrubber = value;
            return this;
        }

        public Builder moderationLayer(ModerationLayer value) {
            this.moderationLayer = value;
            return this;
        }

        public Builder idHasher(IdHasher value) {
            this.idHasher = value;
            return this;
        }

        public Builder actionSender(ModerationActionSender value) {
            this.actionSender = value;
            return this;
        }

        public Builder reviewRecorder(ModerationReviewRecorder value) {
            this.reviewRecorder = value;
            return this;
        }

        public Builder bannedWordDetector(BannedWordDetector value) {
            this.bannedWordDetector = value;
            return this;
        }

        public Builder repeatedMessageDetector(RepeatedMessageDetector value) {
            this.repeatedMessageDetector = value;
            return this;
        }

        public Builder callbackRouter(CallbackRouter value) {
            this.callbackRouter = value;
            return this;
        }

        public Builder joinVerificationService(JoinVerificationService value) {
            this.joinVerificationService = value;
            return this;
        }

        public UpdateDispatcher build() {
            return new UpdateDispatcher(this);
        }
    }

    private UpdateDispatcher(Builder b) {
        this.middlewareChain = b.middlewareChain;
        this.commandDispatcher = b.commandDispatcher;
        this.scrubber = b.scrubber;
        this.moderationLayer = b.moderationLayer;
        this.idHasher = b.idHasher;
        this.enforcer = new ModerationEnforcer(b.actionSender);
        this.reviewRecorder = b.reviewRecorder == null ? ModerationReviewRecorder.noop() : b.reviewRecorder;
        this.bannedWordDetector = b.bannedWordDetector;
        this.repeatedMessageDetector = b.repeatedMessageDetector;
        this.callbackRouter = b.callbackRouter;
        this.joinVerificationService = b.joinVerificationService;
    }

    public UpdateDispatcher(MiddlewareChain middlewareChain, CommandDispatcher commandDispatcher) {
        this(middlewareChain, commandDispatcher, new MessageScrubber(), null, IdHasher.fromEnvironment(),
                ModerationActionSender.noop(), ModerationReviewRecorder.noop(), null);
    }

    public UpdateDispatcher(MiddlewareChain middlewareChain,
                            CommandDispatcher commandDispatcher,
                            MessageScrubber scrubber) {
        this(middlewareChain, commandDispatcher, scrubber, null, IdHasher.fromEnvironment(),
                ModerationActionSender.noop(), ModerationReviewRecorder.noop(), null);
    }

    /**
     * @param moderationLayer 审核层；为 null 表示不启用审核（此时不会挂载判定结果）
     */
    public UpdateDispatcher(MiddlewareChain middlewareChain,
                            CommandDispatcher commandDispatcher,
                            MessageScrubber scrubber,
                            ModerationLayer moderationLayer) {
        this(middlewareChain, commandDispatcher, scrubber, moderationLayer, IdHasher.fromEnvironment(),
                ModerationActionSender.noop(), ModerationReviewRecorder.noop(), null);
    }

    /**
     * @param idHasher 日志脱敏用的标识哈希器——用户/群 id 不得以明文进日志
     */
    public UpdateDispatcher(MiddlewareChain middlewareChain,
                            CommandDispatcher commandDispatcher,
                            MessageScrubber scrubber,
                            ModerationLayer moderationLayer,
                            IdHasher idHasher) {
        this(middlewareChain, commandDispatcher, scrubber, moderationLayer, idHasher,
                ModerationActionSender.noop(), ModerationReviewRecorder.noop(), null);
    }

    /**
     * @param actionSender 硬红线封禁等额外动作的主动通道；未装配场景应传 {@link ModerationActionSender#noop()}
     */
    public UpdateDispatcher(MiddlewareChain middlewareChain,
                            CommandDispatcher commandDispatcher,
                            MessageScrubber scrubber,
                            ModerationLayer moderationLayer,
                            IdHasher idHasher,
                            ModerationActionSender actionSender) {
        this(middlewareChain, commandDispatcher, scrubber, moderationLayer, idHasher, actionSender,
                ModerationReviewRecorder.noop(), null);
    }

    /**
     * @param reviewRecorder 中高风险命中的复核入队通道；未装配场景应传 {@link ModerationReviewRecorder#noop()}
     */
    public UpdateDispatcher(MiddlewareChain middlewareChain,
                            CommandDispatcher commandDispatcher,
                            MessageScrubber scrubber,
                            ModerationLayer moderationLayer,
                            IdHasher idHasher,
                            ModerationActionSender actionSender,
                            ModerationReviewRecorder reviewRecorder) {
        this(middlewareChain, commandDispatcher, scrubber, moderationLayer, idHasher, actionSender,
                reviewRecorder, null);
    }

    /**
     * @param bannedWordDetector 按群违禁词检测器；为 null 表示该能力未装配
     */
    public UpdateDispatcher(MiddlewareChain middlewareChain,
                            CommandDispatcher commandDispatcher,
                            MessageScrubber scrubber,
                            ModerationLayer moderationLayer,
                            IdHasher idHasher,
                            ModerationActionSender actionSender,
                            ModerationReviewRecorder reviewRecorder,
                            BannedWordDetector bannedWordDetector) {
        this(middlewareChain, commandDispatcher, scrubber, moderationLayer, idHasher, actionSender,
                reviewRecorder, bannedWordDetector, null);
    }

    /**
     * @param repeatedMessageDetector 反刷屏（重复内容）检测器；为 null 表示该能力未装配
     */
    public UpdateDispatcher(MiddlewareChain middlewareChain,
                            CommandDispatcher commandDispatcher,
                            MessageScrubber scrubber,
                            ModerationLayer moderationLayer,
                            IdHasher idHasher,
                            ModerationActionSender actionSender,
                            ModerationReviewRecorder reviewRecorder,
                            BannedWordDetector bannedWordDetector,
                            RepeatedMessageDetector repeatedMessageDetector) {
        this(builder()
                .middlewareChain(middlewareChain)
                .commandDispatcher(commandDispatcher)
                .scrubber(scrubber)
                .moderationLayer(moderationLayer)
                .idHasher(idHasher)
                .actionSender(actionSender)
                .reviewRecorder(reviewRecorder)
                .bannedWordDetector(bannedWordDetector)
                .repeatedMessageDetector(repeatedMessageDetector));
    }

    public Optional<BotApiMethod<?>> dispatch(Update update) throws Exception {
        try {
            if (update == null) {
                return Optional.empty();
            }

            // 按钮回调是独立入口：它没有 message/edited_message/channel_post，
            // 因此必须在 relevantMessage 之前分流，否则会被当成"无内容更新"静默丢弃。
            if (callbackRouter != null && update.hasCallbackQuery()) {
                return callbackRouter.route(update.getCallbackQuery());
            }

            Message message = relevantMessage(update);

            // 入群事件（模块四）：新成员需先通过验证才能留群。
            // 验证消息由主动通道逐条发出（一次入群可能带多名成员），故此分支不返回方法。
            if (joinVerificationService != null && message != null
                    && message.getNewChatMembers() != null && !message.getNewChatMembers().isEmpty()) {
                joinVerificationService.onMembersJoined(message);
                return Optional.empty();
            }
            UpdateContext ctx = toContext(update, message);
            moderateInto(ctx, message);

            // 审核命中的处置<b>优先于一切</b>：违规消息不再走中间件链与命令分发。
            // 否则一条既违规又带命令的消息会先被执行、再被删除——本末倒置。
            Optional<BotApiMethod<?>> enforced = enforcer.enforce(ctx);
            if (enforced.isPresent()) {
                return enforced;
            }

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
        if (message == null || (moderationLayer == null && bannedWordDetector == null
                && repeatedMessageDetector == null)) {
            return;
        }

        // 豁免条件：这条消息**会真的执行一条命令**（已注册 + 发送者有权限 + 群开关允许）。
        //
        // 不能用 `message.isCommand()`——那只是"文本看起来像命令"（offset 0 的 bot_command entity），
        // 与命令是否注册、发送者有无权限无关。拿它当豁免依据，任何人只要把违规内容写成
        // `/任意词 <违规内容>` 就能绕过内容审核：消息不被删，而命令又因未注册/无权限而不执行，
        // 违规内容于是留在群里。这是提交后审查抓到的 HIGH 绕过。
        //
        // 豁免的只是「审核」；消息正文仍会被 finally 里的 scrub 照常清除。
        if (commandDispatcher.willExecute(ctx)) {
            return;
        }

        String content = contentOf(message);

        ModerationVerdict l1 = moderationLayer == null
                ? null
                : moderationLayer.inspect(content)
                        .map(hit -> new ModerationVerdict(hit.riskLevel(), hit.hardLine(), List.of(hit.ruleId())))
                        .orElse(null);
        ModerationVerdict bannedWord = bannedWordDetector == null
                ? null
                : bannedWordDetector.inspect(ctx.chatId(), content).orElse(null);
        // 反刷屏：需要 userId 才能归因到"同一用户重复"
        ModerationVerdict flood = repeatedMessageDetector == null
                ? null
                : repeatedMessageDetector.inspect(ctx.chatId(), ctx.userId(), content).orElse(null);

        ModerationVerdict verdict = worseOf(worseOf(l1, bannedWord), flood);
        if (verdict == null) {
            // 有审核能力但都没命中：仍挂 clean，让下游能区分「审过且干净」与「压根没审」。
            verdict = ModerationVerdict.clean();
        }

        ctx.attach(verdict);

        if (verdict.needsReview()) {
            // 审计日志：不记正文、不记命中片段（避免经日志这条侧路泄露原文）。
            // 用户/群标识**必须哈希化**——V5.0 明确要求，明文 id 属个人数据处理。
            log.info("审核命中：rule={} level={} hardLine={} chatHash={} userHash={}",
                    verdict.matchedRuleIds(), verdict.riskLevel(), verdict.hardLine(),
                    idHasher.hash(ctx.chatId()), idHasher.hash(ctx.userId()));
        }

        // 中高风险（非硬红线）入队待人工复核：硬红线走「立即删除 + 封禁」不等复核，
        // clean 无需复核。入队失败不影响主链路（由 recorder 实现 fail-open）。
        if (verdict.needsReview() && !verdict.shouldFreezeImmediately()) {
            reviewRecorder.record(ctx, verdict);
        }
    }

    /**
     * 取两个判定中更严重的一个：硬红线优先，其次比较风险等级；都为 null 返回 null。
     *
     * <p>L1 正则层与按群词库是<b>并列</b>的两类检测，谁更严重谁生效——
     * 不能因为词库命中就掩盖 L1 的高风险命中（反之亦然）。
     */
    private static ModerationVerdict worseOf(ModerationVerdict a, ModerationVerdict b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.hardLine()) {
            return a;
        }
        if (b.hardLine()) {
            return b;
        }
        return a.riskLevel().severity() >= b.riskLevel().severity() ? a : b;
    }

    /**
     * 拼出待审核的内容。
     *
     * <p>与 {@link MessageScrubber} 的口径保持一致：文本消息看 {@code text}，
     * 媒体消息看 {@code caption}，两者都有则拼接（图片带长文说明的情况真实存在）。
     *
     * <p><b>投票与地点也必须看</b>：清除端会把这几个字段一并清掉，
     * 审核端若只看 text/caption，则"投票问题/选项"与"地点标题/地址"既不被审、也不被清——
     * 那是**假 clean**（让下游以为已检查过），比不审更危险。
     */
    static String contentOf(Message message) {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, message.getText());
        appendPart(sb, message.getCaption());

        Poll poll = message.getPoll();
        if (poll != null) {
            appendPart(sb, poll.getQuestion());
            if (poll.getOptions() != null) {
                for (PollOption option : poll.getOptions()) {
                    appendPart(sb, option == null ? null : option.getText());
                }
            }
        }

        Venue venue = message.getVenue();
        if (venue != null) {
            appendPart(sb, venue.getTitle());
            appendPart(sb, venue.getAddress());
        }

        // 投票选项的增删事件带 optionText——清除端会清掉它们，审核端也必须看：
        // 否则是"被清但没审"，违规词既拦不住、事后也无痕迹。
        PollOptionAdded added = message.getPollOptionAdded();
        if (added != null) {
            appendPart(sb, added.getOptionText());
        }
        PollOptionDeleted deleted = message.getPollOptionDeleted();
        if (deleted != null) {
            appendPart(sb, deleted.getOptionText());
        }

        // 链接预览选项里唯一的文本字段是 url（其余为展示开关）
        LinkPreviewOptions preview = message.getLinkPreviewOptions();
        if (preview != null) {
            appendPart(sb, preview.getUrlField());
        }

        return sb.isEmpty() ? null : sb.toString();
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (part == null || part.isEmpty()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(part);
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
        // messageId 供处置动作定位目标（如删除违规消息）
        // commandArgs 供管理命令接收操作数（见 UpdateContext 的「受限例外」说明）
        return new UpdateContext(update.getUpdateId(), userId, chatId, message.getMessageId(),
                message.getCommand(), extractCommandArgs(message));
    }

    /**
     * 提取命令操作数（命令名之后的文本）。
     *
     * <p>只在<b>命令消息</b>上返回值：非命令消息一律 {@code null}——
     * 这样 {@link UpdateContext#commandArgs()} 就不会成为"消息正文"的侧路。
     * 命令名可能带 {@code @BotName} 后缀（{@code /addword@MyBot 词}），
     * 故按第一个空白切分、取其后全部内容。
     */
    static String extractCommandArgs(Message message) {
        if (message == null || !message.isCommand()) {
            return null;
        }
        String text = message.getText();
        if (text == null || text.isEmpty()) {
            return null;
        }
        int sep = indexOfWhitespace(text);
        if (sep < 0) {
            return null;
        }
        String args = text.substring(sep + 1).trim();
        return args.isEmpty() ? null : args;
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}

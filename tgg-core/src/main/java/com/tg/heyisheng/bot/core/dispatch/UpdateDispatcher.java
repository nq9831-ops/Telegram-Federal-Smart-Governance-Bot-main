package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.admission.JoinVerificationService;
import com.tg.heyisheng.bot.core.audit.AuditEntry;
import com.tg.heyisheng.bot.core.audit.AuditService;
import com.tg.heyisheng.bot.core.callback.CallbackRouter;
import com.tg.heyisheng.bot.core.credit.CreditEvent;
import com.tg.heyisheng.bot.core.credit.CreditEventSink;
import com.tg.heyisheng.bot.core.credit.CreditEventType;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.core.membership.MemberJoinRecorder;
import com.tg.heyisheng.bot.core.middleware.MiddlewareChain;
import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import com.tg.heyisheng.bot.core.moderation.ModerationCaseRef;
import com.tg.heyisheng.bot.core.moderation.ModerationEnforcer;
import com.tg.heyisheng.bot.core.moderation.ModerationLayer;
import com.tg.heyisheng.bot.core.moderation.ModerationPipeline;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRecorder;
import com.tg.heyisheng.bot.core.moderation.ModerationVerdict;
import com.tg.heyisheng.bot.core.moderation.RepeatedMessageDetector;
import com.tg.heyisheng.bot.core.moderation.SensitiveTopicGuard;
import com.tg.heyisheng.bot.core.privacy.MessageScrubber;
import com.tg.heyisheng.bot.core.wordfilter.BannedWordDetector;
import com.tg.heyisheng.bot.core.wordfilter.TaughtRuleDetector;
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
    private final ModerationPipeline moderationPipeline;
    private final IdHasher idHasher;
    private final ModerationEnforcer enforcer;
    private final ModerationReviewRecorder reviewRecorder;
    private final BannedWordDetector bannedWordDetector;
    private final RepeatedMessageDetector repeatedMessageDetector;
    /** 按群教学规则检测器（模块九 §10.3）；为 null 表示该能力未装配。 */
    private final TaughtRuleDetector taughtRuleDetector;
    /** 敏感话题的分级与<b>递进处置</b>编排（模块九 §10.5，按群 + 群标签豁免）；为 null 表示未启用。 */
    private final SensitiveTopicGuard sensitiveTopicGuard;
    private final CallbackRouter callbackRouter;
    private final JoinVerificationService joinVerificationService;
    /** 信用事件发布通道（模块七）；默认 noop——未装配信用分时主链路零影响。 */
    private final CreditEventSink creditEventSink;
    /** 成员入群时间采集（模块九 §10.3「入群时长」门槛的数据源）；为 null 表示该能力未装配。 */
    private final MemberJoinRecorder memberJoinRecorder;
    /**
     * 审计通道；为 {@code null} 表示未装配（与 creditEventSink 同款：未装配即零影响）。
     *
     * <p><b>为什么审核路径必须自己写审计</b>：审计切面（{@code AuditAspect}）只切
     * {@code CommandHandler#handle}——审核走的是**消息路径**，既不经切面也不在 tgg-admin，
     * 所以此前「谁被处置、何时、命中了什么」在审计里是一片空白。而「可解释、可追踪」
     * 正是本项目八条核心原则的头两条，后台案件时间线也依赖它。
     */
    private final AuditService auditService;

    /**
     * <b>唯一的公开装配入口</b>。
     *
     * <p>构造重载已全部移除：它们逐参递增（最多到 10 个），每加一个审核能力就得再加一个重载，
     * 形成「加能力 = 加重载」的循环，而生产侧自始至终只走 {@code builder()}。
     * 现在新增能力只需在 Builder 上加一个字段，既有装配点一律不受影响。
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
        private ModerationPipeline moderationPipeline;
        private IdHasher idHasher = IdHasher.fromEnvironment();
        private ModerationActionSender actionSender = ModerationActionSender.noop();
        private ModerationReviewRecorder reviewRecorder = ModerationReviewRecorder.noop();
        private BannedWordDetector bannedWordDetector;
        private RepeatedMessageDetector repeatedMessageDetector;
        private TaughtRuleDetector taughtRuleDetector;
        private SensitiveTopicGuard sensitiveTopicGuard;
        private CallbackRouter callbackRouter;
        private JoinVerificationService joinVerificationService;
        private CreditEventSink creditEventSink = CreditEventSink.noop();
        private MemberJoinRecorder memberJoinRecorder;
        private AuditService auditService;

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

        /**
         * 用四层流水线替代单层审核（模块九）。
         *
         * <p>两者取其一：给了 pipeline 就用它（L1..L4 按序、命中即短路），
         * 否则回落到单层 {@link #moderationLayer}——保持既有调用方与测试不变。
         */
        public Builder moderationPipeline(ModerationPipeline value) {
            this.moderationPipeline = value;
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

        public Builder taughtRuleDetector(TaughtRuleDetector value) {
            this.taughtRuleDetector = value;
            return this;
        }

        /** 敏感话题分级与递进处置（模块九 §10.5）；不设置即关闭该能力。 */
        public Builder sensitiveTopicGuard(SensitiveTopicGuard value) {
            this.sensitiveTopicGuard = value;
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

        /** 信用事件发布通道（模块七）；不设置即 noop。 */
        public Builder creditEventSink(CreditEventSink value) {
            this.creditEventSink = value;
            return this;
        }

        /** 成员入群时间采集（模块九 §10.3）；不设置即关闭该能力（chat_member 更新被忽略）。 */
        public Builder memberJoinRecorder(MemberJoinRecorder value) {
            this.memberJoinRecorder = value;
            return this;
        }

        /** 审计通道；不设置即不写审核审计（既有装配与单测行为不变）。 */
        public Builder auditService(AuditService value) {
            this.auditService = value;
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
        this.moderationPipeline = b.moderationPipeline;
        this.idHasher = b.idHasher;
        this.enforcer = new ModerationEnforcer(b.actionSender);
        this.reviewRecorder = b.reviewRecorder == null ? ModerationReviewRecorder.noop() : b.reviewRecorder;
        this.bannedWordDetector = b.bannedWordDetector;
        this.repeatedMessageDetector = b.repeatedMessageDetector;
        this.taughtRuleDetector = b.taughtRuleDetector;
        this.sensitiveTopicGuard = b.sensitiveTopicGuard;
        this.callbackRouter = b.callbackRouter;
        this.joinVerificationService = b.joinVerificationService;
        this.creditEventSink = b.creditEventSink == null ? CreditEventSink.noop() : b.creditEventSink;
        this.memberJoinRecorder = b.memberJoinRecorder;
        this.auditService = b.auditService;
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

            // 成员状态变化（模块九 §10.3「入群时长」的唯一数据源）：chat_member 更新**没有 message**，
            // 故必须在此处（relevantMessage 之前）**无条件**分流——与 callback query 同构。
            // 若只按 recorder 是否装配来分流，未装配时它会带着 ChatMemberUpdated 走完整条链路
            // （中间件与命令分发既拿不到 chatId 也拿不到 userId），然后被当成「无内容更新」静默丢弃。
            // 那正是「采集上线了却一条都没记下」最隐蔽的失败形态，故这里不留给装配状态决定。
            if (update.hasChatMember()) {
                if (memberJoinRecorder != null) {
                    memberJoinRecorder.onChatMemberUpdated(update.getChatMember());
                }
                return Optional.empty();
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
                // 审计**在动作真的发生之后**写——记「实际执行了什么」，而不是「本来打算做什么」。
                recordModerationAudit(ctx, enforced.get());
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
     * <p><b>为什么拆成四段</b>：本方法原先是约百行的过程，混了三件<b>正交</b>的事——
     * ①并列检测、②判定留痕与入队、③信用事件发布。任一件改动都要通读整段才能确认影响面。
     *
     * <p><b>这里聚合的是「调用侧编排」，不是检测器的契约</b>：各检测器仍各自独立
     * （见 {@link BannedWordDetector} 关于「不统一为单一接口」的理由——「按群词库」与
     * 「通用规则层」是两类不同的东西，强行统一会迫使改签名并波及既有层与测试）。
     *
     * <p>未命中时也挂载 {@link ModerationVerdict#clean()}——这样下游能区分
     * 「审过且干净」与「压根没审」，避免把"没审核"误当成"审核通过"。
     */
    private void moderateInto(UpdateContext ctx, Message message) {
        if (message == null || !hasAnyDetector()) {
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

        // 敏感话题是**唯一带副作用**的检测（命中即发警告 / 禁言），故不并入并列检测，
        // 只把它的判定结果并入「取最严重」。
        SensitiveTopicGuard.Outcome sensitive = sensitiveTopicOutcome(ctx, content);

        ModerationVerdict verdict = worseOf(detectAll(ctx, content), sensitiveVerdict(sensitive));
        if (verdict == null) {
            // 有审核能力但都没命中：仍挂 clean，让下游能区分「审过且干净」与「压根没审」。
            verdict = ModerationVerdict.clean();
        }

        ctx.attach(verdict);
        // 入队回填的案件号挂到上下文：处置阶段的群内告知要给出编号，当事人才有据可申诉。
        // 未入队（clean / 空实现 / 失败）时不挂——消费方据此降级为不带编号的告知。
        recordIfNeeded(ctx, verdict).ifPresent(id -> ctx.attach(new ModerationCaseRef(id)));
        publishCreditEventIfNeeded(ctx, verdict, sensitive);
    }

    /** 是否装配了任一检测能力——都没装就不必走审核这一段。 */
    private boolean hasAnyDetector() {
        return moderationLayer != null || moderationPipeline != null || bannedWordDetector != null
                || repeatedMessageDetector != null || taughtRuleDetector != null
                || sensitiveTopicGuard != null;
    }

    /**
     * 并列检测：各检测器独立判定，取最严重的一条（无命中时为 null）。
     *
     * <p>书写顺序与既有实现一致（L1/流水线 → 词库 → 反刷屏 → 教学规则），便于与历史行为对照。
     */
    private ModerationVerdict detectAll(UpdateContext ctx, String content) {
        // 四层审核（模块九）：优先用流水线（L1→L2→L3→L4 按序、命中即短路）；
        // 未装配流水线时回落到单层——保持既有调用方行为逐字不变。
        Optional<ModerationLayer.LayerHit> layerHit = moderationPipeline != null
                ? moderationPipeline.inspect(content)
                : (moderationLayer == null ? Optional.empty() : moderationLayer.inspect(content));
        ModerationVerdict l1 = layerHit
                .map(h -> new ModerationVerdict(h.riskLevel(), h.hardLine(), List.of(h.ruleId())))
                .orElse(null);
        ModerationVerdict bannedWord = bannedWordDetector == null
                ? null
                : bannedWordDetector.inspect(ctx.chatId(), content).orElse(null);
        // 反刷屏：需要 userId 才能归因到"同一用户重复"
        ModerationVerdict flood = repeatedMessageDetector == null
                ? null
                : repeatedMessageDetector.inspect(ctx.chatId(), ctx.userId(), content).orElse(null);
        // 按群教学规则（模块九 §10.3 的 /teach）：与违禁词同为「按群」能力，
        // 并列参与 worseOf 取最严重——低等级命中不得掩盖高等级（既有纪律）。
        ModerationVerdict taught = taughtRuleDetector == null
                ? null
                : taughtRuleDetector.inspect(ctx.chatId(), content).orElse(null);

        return worseOf(worseOf(worseOf(l1, bannedWord), flood), taught);
    }

    /**
     * 敏感话题分级 + <b>递进处置</b>（模块九 §10.5）：按群 + 群标签豁免，故需要 chatId；
     * 无群 id（服务类更新）时跳过。警告 / 禁言等主动动作在 guard 内完成；其判定结果仍走
     * 「删消息保底 + 入队复核 + 信用事件」——删除统一由 enforcer 负责，不重复。
     */
    private SensitiveTopicGuard.Outcome sensitiveTopicOutcome(UpdateContext ctx, String content) {
        return (sensitiveTopicGuard == null || ctx.chatId() == null)
                ? null
                : sensitiveTopicGuard.handle(ctx.chatId(), ctx.userId(), content).orElse(null);
    }

    private static ModerationVerdict sensitiveVerdict(SensitiveTopicGuard.Outcome outcome) {
        return outcome == null ? null : outcome.verdict();
    }

    /** 审核处置的审计动作标识（与 tgg-admin 的 {@code admin.config.update} 同风格，可读优先）。 */
    static final String MODERATION_AUDIT_ACTION = "moderation.enforce";

    /**
     * 审核处置的审计留痕（模块十 §11.2）。
     *
     * <p><b>为什么不走审计切面</b>：{@code AuditAspect} 只切 {@code CommandHandler#handle}，
     * 而审核走的是**消息路径**（message → moderateInto → enforcer），既不经切面、也不在 tgg-admin。
     * 不写这一条，「谁被处置、何时、因为什么」在审计里就是空白——后台的案件时间线与用户的处罚史
     * 都无从谈起（原文的「可解释、可追踪」两条原则也就落不了地）。
     *
     * <p><b>只记结论</b>：动作类型 + 风险等级 + 是否红线。<b>不记正文、不记命中片段</b>；
     * 规则 id 留在 {@code moderation_review_queue} 里，审计不重复也不至于撑长 detail。
     *
     * <p><b>actor 取被处置者</b>：本表的 {@code idx_audit_actor_time} 与
     * {@code ExportMyDataCommandHandler} 都按 {@code actor_id} 查——被处置记录属于当事人有权
     * 查阅与导出的范围，填 {@code null} 会让这两条路径都查不到（与命令路径的 actor=发起者同构）。
     */
    private void recordModerationAudit(UpdateContext ctx, BotApiMethod<?> action) {
        if (auditService == null) {
            return;
        }
        Long caseId = ctx.find(ModerationCaseRef.class).map(ModerationCaseRef::caseId).orElse(null);
        String actionName = action.getClass().getSimpleName();
        String detail = ctx.find(ModerationVerdict.class)
                .map(v -> "action=" + actionName + " level=" + v.riskLevel()
                        + " hardLine=" + v.hardLine())
                .orElse("action=" + actionName);
        auditService.record(ctx.userId(), MODERATION_AUDIT_ACTION, ctx.chatId(), caseId,
                AuditEntry.Outcome.SUCCESS, detail);
    }

    /**
     * 判定留痕与复核入队（仅命中时）。
     *
     * <p>中高风险与硬红线<b>都入队</b>：clean 无需复核；硬红线虽已「立即删除 + 封禁」、
     * 不走放行复核，但须留痕以支持操作员<b>事后推翻误封</b>（解封）——这是「推翻权」
     * 最有分量的一半（§10.4.4）。入队失败不影响主链路（recorder 实现 fail-open）。
     *
     * @return 入队成功时的案件号；未入队（clean）或入队失败时为空
     */
    private Optional<Long> recordIfNeeded(UpdateContext ctx, ModerationVerdict verdict) {
        if (!verdict.needsReview()) {
            return Optional.empty();
        }
        // 审计日志：不记正文、不记命中片段（避免经日志这条侧路泄露原文）。
        // 用户/群标识**必须哈希化**——V5.0 明确要求，明文 id 属个人数据处理。
        log.info("审核命中：rule={} level={} hardLine={} chatHash={} userHash={}",
                verdict.matchedRuleIds(), verdict.riskLevel(), verdict.hardLine(),
                idHasher.hash(ctx.chatId()), idHasher.hash(ctx.userId()));
        return reviewRecorder.record(ctx, verdict);
    }

    /**
     * 信用事件（模块七）：与复核入队<b>并列</b>发布，互不影响。
     *
     * <p>硬红线与中高风险都发布——分值差异由规则引擎按 hardLine 判定，不在此处区分。
     * userId 为空（服务类更新）时跳过，不让信用事件成为新的空指针源。
     */
    private void publishCreditEventIfNeeded(UpdateContext ctx,
                                            ModerationVerdict verdict,
                                            SensitiveTopicGuard.Outcome sensitive) {
        if (!verdict.needsReview() || ctx.userId() == null) {
            return;
        }
        // 敏感话题第 3 档 → **显式**要求上报联邦（§10.5「三次联邦标记」）。
        // 不能指望分数阈值：原文的 100−5−15−30 = 50，永远到不了 ≤0 的触发线。
        boolean federationReport = sensitive != null
                && sensitive.strike() >= SensitiveTopicGuard.FEDERATION_STRIKE;
        // 幂等键 = 「群 + 消息」这一**稳定业务标识**：Telegram 重投同一条 update 时它不变，
        // 故信用分不会被二次扣减（§3.3）。注意不可用 eventId（每次新 UUID）做去重。
        // messageId 缺失（频道帖等）时退化为 null —— 即不去重，宁可多记也不误杀。
        String idempotencyKey = ctx.messageId()
                .map(mid -> "moderation:" + ctx.chatId() + ":" + mid)
                .orElse(null);
        creditEventSink.publish(CreditEvent.of(
                CreditSubjectType.INDIVIDUAL, ctx.userId(),
                CreditEventType.MODERATION_HIT, verdict.riskLevel(),
                verdict.hardLine(), "moderation", federationReport, idempotencyKey));
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

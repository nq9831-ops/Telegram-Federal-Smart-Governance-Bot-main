package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.core.credit.CreditEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.UnbanChatMember;
import org.telegram.telegrambots.meta.api.objects.ChatPermissions;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 人工复核裁决（模块九 §10.4 · <b>操作员推翻权</b>）。
 *
 * <p><b>它填补的空洞</b>：队列此前只入不裁（{@code ModerationReviewQueueService} 只有 {@code record}），
 * 「系统判错了 → 人来改判」这条路不存在。本服务让操作员对队列项下结论，并把结论落成<b>实际动作</b>。
 *
 * <p><b>两份权力</b>：
 * <ul>
 *   <li><b>推翻（REJECTED）</b>——判定误报；若原判为硬红线（命中即自动封禁），则<b>解封</b>。
 *       这是「推翻权」最有分量的一半：防的是<b>误封真人</b>。</li>
 *   <li><b>维持（APPROVED）</b>——确认违规；HIGH 追加禁言 24h。</li>
 * </ul>
 *
 * <p><b>不可自审</b>：裁决人恰是被判定消息的发布者时拒绝（{@code SELF_DECISION_FORBIDDEN}）。
 * 这条规则**刻意放在本类**（各入口共用的裁决服务）而不是某个适配层——放在 Web 侧曾导致
 * 「群里能审自己的案子、后台不能审」的两端漂移；下沉到这里，则 Telegram 命令、Web 后台
 * 以及将来任何新入口**一律**生效，规则只有一份实现。
 *
 * <p><b>幂等</b>：已终态的项不再改动，返回既有结论——重复点击/重放不会二次处置
 * （尤其不会重复解封或重复禁言）。
 *
 * <p><b>隐私</b>：队列不含正文，裁决备注亦不得写入正文（{@code note} 由命令层传入，见命令的约束）。
 */
@Service
public class ModerationReviewDecisionService {

    private static final Logger log = LoggerFactory.getLogger(ModerationReviewDecisionService.class);

    /** 「维持」HIGH 违规时的禁言时长（保守值；真实封禁/解封效果属部署后验证项）。 */
    static final Duration MUTE_DURATION = Duration.ofHours(24);

    /** 裁决备注长度上限——与 {@code moderation_review_queue.note} 的列宽一致。 */
    public static final int MAX_NOTE_LENGTH = 255;

    private final ModerationReviewRepository repository;
    private final ModerationActionSender actionSender;
    /** 信用分通道；{@code null} = 未装配（模块七未启用）——推翻照常，只是不退分。 */
    private final CreditEventSink creditSink;
    private final Clock clock;

    @Autowired
    public ModerationReviewDecisionService(ModerationReviewRepository repository,
                                           ModerationActionSender actionSender,
                                           ObjectProvider<CreditEventSink> creditSinkProvider) {
        this(repository, actionSender, creditSinkProvider.getIfAvailable(), Clock.systemUTC());
    }

    /** 兼容构造器（单测 / 无信用分模块）：不装配退分通道。 */
    ModerationReviewDecisionService(ModerationReviewRepository repository,
                                    ModerationActionSender actionSender,
                                    Clock clock) {
        this(repository, actionSender, (CreditEventSink) null, clock);
    }

    ModerationReviewDecisionService(ModerationReviewRepository repository,
                                    ModerationActionSender actionSender,
                                    CreditEventSink creditSink,
                                    Clock clock) {
        this.repository = repository;
        this.actionSender = actionSender == null ? ModerationActionSender.noop() : actionSender;
        this.creditSink = creditSink;
        this.clock = clock;
    }

    /** 待裁决项（按入队顺序）。 */
    public List<ModerationReviewItem> listPending() {
        return repository.findByStatusOrderByIdAsc(ReviewStatus.PENDING);
    }

    /**
     * 裁决一条队列项。
     *
     * @param id       队列项 id
     * @param decision {@link ReviewStatus#APPROVED}（维持）或 {@link ReviewStatus#REJECTED}（推翻）
     * @param operator 裁决人 userId（全局白名单，由命令层门控）
     * @param note     裁决备注（不含正文，可为空）
     */
    @Transactional
    public Outcome decide(long id, ReviewStatus decision, Long operator, String note) {
        if (decision != ReviewStatus.APPROVED && decision != ReviewStatus.REJECTED) {
            throw new IllegalArgumentException("裁决结论只能是 APPROVED（维持）或 REJECTED（推翻）");
        }
        // 列宽 255：不拦的话超长备注会在 flush 期以 DataIntegrityViolationException 炸掉整个事务
        // ——裁决回滚、连回执都没有（提交后审查抓到的 CRITICAL）。在此显式拒绝，让命令层给出可读提示。
        String cleanNote = note == null ? null : note.trim();
        if (cleanNote != null && cleanNote.length() > MAX_NOTE_LENGTH) {
            throw new TggException("裁决备注过长（上限 " + MAX_NOTE_LENGTH + " 字符）");
        }
        Optional<ModerationReviewItem> found = repository.findById(id);
        if (found.isEmpty()) {
            return Outcome.notFound();
        }
        ModerationReviewItem item = found.get();
        // 「不可自审」：判据是「被判定消息的发布者 == 裁决人」。放在这里（共用裁决服务）而非各入口，
        // 是为了让 Web 后台、/review_* 与将来任何入口**一律**生效——规则只写一份，才不会两端漂移
        // （此前校验只写在 tgg-admin 的适配层，于是同一件事在群里能审、在后台不能审）。
        // 位置在「幂等判定之前」：不可自审是合规约束，优先于「这条已经裁过了」的状态判断。
        if (item.getUserId() != null && item.getUserId().equals(operator)) {
            return Outcome.selfDecisionForbidden(item.getStatus());
        }
        if (!item.isPending()) {
            return Outcome.alreadyDecided(item.getStatus());
        }

        item.decide(decision, operator, cleanNote, clock.instant());
        repository.save(item);
        enforce(item, decision);

        if (decision == ReviewStatus.REJECTED) {
            // 推翻 = 「这次判定错了」，把该次命中扣掉的信用分还回去——这是「推翻权」的完整含义：
            // 只解封不退分，当事人仍会卡在信用分阈值触发的联邦封禁档。
            // 独立于 enforce：退分只需 (chatId, messageId)，不依赖 userId 是否齐备。
            refundCreditFor(item);
        }

        log.info("复核裁决：id={} decision={} hardLine={} level={} operator={}",
                id, decision, item.isHardLine(), item.getRiskLevel(), operator);
        return Outcome.decided(item);
    }

    /**
     * 推翻案件后退回该次命中<b>实际</b>扣掉的信用分（模块十一）。
     *
     * <p><b>幂等键由案件精确推导</b>：扣分事件用的是 {@code moderation:<chatId>:<messageId>}
     * （与入队同一 {@code UpdateContext}），故此处用同一对字段重建——<b>不是</b>猜。
     * 案件缺 {@code messageId}（频道帖）时无从推导，<b>不退分</b>并留日志：宁可不退，
     * 也不能拿一个凑出来的键去查（可能命中别人的流水）。
     *
     * <p><b>不退分也不影响裁决</b>：信用分是增强环节（与 {@code CreditEventSink#publish} 同款取舍），
     * 未装配模块七时 {@code creditSink} 为 {@code null}，本方法直接返回。
     */
    private void refundCreditFor(ModerationReviewItem item) {
        if (creditSink == null) {
            return;
        }
        if (item.getMessageId() == null) {
            log.warn("案件缺 messageId，无法推导信用幂等键——跳过退分：caseId={}", item.getId());
            return;
        }
        try {
            String original = "moderation:" + item.getChatId() + ":" + item.getMessageId();
            String reversal = "moderation-reversal:" + item.getChatId() + ":" + item.getMessageId();
            boolean refunded = creditSink.reverse(original, reversal, "case-rejected:" + item.getId());
            log.info("推翻案件退分：caseId={} refunded={}", item.getId(), refunded);
        } catch (RuntimeException ex) {
            // 实现契约要求 sink 自行吞异常；此处再兜一层，确保退分永不阻断裁决。
            log.error("推翻案件退分失败，已忽略：caseId={}", item.getId(), ex);
        }
    }

    /**
     * 裁决的处置执行。
     *
     * <p>实现自行吞异常（经 {@link ModerationActionSender} 契约）：单条处置失败不得让裁决本身回滚
     * ——状态已落库，动作失败靠日志可追。
     */
    private void enforce(ModerationReviewItem item, ReviewStatus decision) {
        Long chatId = item.getChatId();
        Long userId = item.getUserId();
        if (chatId == null || userId == null) {
            log.warn("裁决缺少 chatId/userId，无法执行处置（仅落状态）：id={} decision={}",
                    item.getId(), decision);
            return;
        }

        if (decision == ReviewStatus.REJECTED) {
            // 推翻：硬红线原判伴随自动封禁，故解封；非硬红线本来只删过消息，无动作可撤销。
            if (item.isHardLine()) {
                actionSender.send(UnbanChatMember.builder()
                        .chatId(chatId)
                        .userId(userId)
                        .onlyIfBanned(true)
                        .build());
            }
            return;
        }

        // 维持：HIGH 追加禁言（防累积违规者）；MEDIUM/LOW 仅记录结论，不加码。
        if (item.getRiskLevel() == RiskLevel.HIGH) {
            actionSender.send(RestrictChatMember.builder()
                    .chatId(chatId)
                    .userId(userId)
                    .permissions(ChatPermissions.builder().build())
                    .untilDate((int) clock.instant().plus(MUTE_DURATION).getEpochSecond())
                    .useIndependentChatPermissions(true)
                    .build());
        }
    }

    /** 裁决结果：区分「未找到 / 已裁决（幂等）/ 不可自审 / 本次裁决」。 */
    public record Outcome(Result result, ReviewStatus status) {

        public enum Result {
            /** 队列中无此 id。 */
            NOT_FOUND,
            /** 该项已有终态结论；本次未改动（幂等返回既有结论）。 */
            ALREADY_DECIDED,
            /** 裁决人正是该案件的当事人——拒绝，且未改动任何状态、未发出任何动作。 */
            SELF_DECISION_FORBIDDEN,
            /** 本次成功裁决。 */
            DECIDED
        }

        static Outcome notFound() {
            return new Outcome(Result.NOT_FOUND, null);
        }

        static Outcome alreadyDecided(ReviewStatus status) {
            return new Outcome(Result.ALREADY_DECIDED, status);
        }

        static Outcome selfDecisionForbidden(ReviewStatus status) {
            return new Outcome(Result.SELF_DECISION_FORBIDDEN, status);
        }

        static Outcome decided(ModerationReviewItem item) {
            return new Outcome(Result.DECIDED, item.getStatus());
        }
    }
}

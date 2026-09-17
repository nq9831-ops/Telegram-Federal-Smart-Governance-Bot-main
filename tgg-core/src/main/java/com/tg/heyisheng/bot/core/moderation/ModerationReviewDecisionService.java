package com.tg.heyisheng.bot.core.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final ModerationReviewRepository repository;
    private final ModerationActionSender actionSender;
    private final Clock clock;

    @Autowired
    public ModerationReviewDecisionService(ModerationReviewRepository repository,
                                           ModerationActionSender actionSender) {
        this(repository, actionSender, Clock.systemUTC());
    }

    ModerationReviewDecisionService(ModerationReviewRepository repository,
                                    ModerationActionSender actionSender,
                                    Clock clock) {
        this.repository = repository;
        this.actionSender = actionSender == null ? ModerationActionSender.noop() : actionSender;
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
        Optional<ModerationReviewItem> found = repository.findById(id);
        if (found.isEmpty()) {
            return Outcome.notFound();
        }
        ModerationReviewItem item = found.get();
        if (!item.isPending()) {
            return Outcome.alreadyDecided(item.getStatus());
        }

        item.decide(decision, operator, note, clock.instant());
        repository.save(item);
        enforce(item, decision);

        log.info("复核裁决：id={} decision={} hardLine={} level={} operator={}",
                id, decision, item.isHardLine(), item.getRiskLevel(), operator);
        return Outcome.decided(item);
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

    /** 裁决结果：区分「未找到 / 已裁决（幂等）/ 本次裁决」。 */
    public record Outcome(Result result, ReviewStatus status) {

        public enum Result {
            /** 队列中无此 id。 */
            NOT_FOUND,
            /** 该项已有终态结论；本次未改动（幂等返回既有结论）。 */
            ALREADY_DECIDED,
            /** 本次成功裁决。 */
            DECIDED
        }

        static Outcome notFound() {
            return new Outcome(Result.NOT_FOUND, null);
        }

        static Outcome alreadyDecided(ReviewStatus status) {
            return new Outcome(Result.ALREADY_DECIDED, status);
        }

        static Outcome decided(ModerationReviewItem item) {
            return new Outcome(Result.DECIDED, item.getStatus());
        }
    }
}

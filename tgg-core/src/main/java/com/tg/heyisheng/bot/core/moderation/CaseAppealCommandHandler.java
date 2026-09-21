package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import org.springframework.beans.factory.annotation.Autowired;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.Clock;
import java.util.Optional;

/**
 * {@code /case_appeal <案件编号> <理由>} —— 对审核案件提交申诉（原文 §2.1/§2.3）。
 *
 * <p><b>解决什么</b>：此前审核告知只说「已被删除、有疑问联系群管理员」——当事人<b>没有可执行的申诉入口</b>，
 * 而「可申诉」是原文的八条核心原则之一。案件号随告知给出（见
 * {@link ModerationMessages#deletedNoticeWithCase}），本命令是它的消费端。
 *
 * <p><b>安全边界（本类唯一但必须死守的一条）</b>：<b>只有案件当事人能申诉</b>。
 * 案件号在群内是公开的（告知里带着），所以「知道编号」不等于「有权申诉」——
 * 若不校验，任何人都能用别人的编号提交申诉，把队列灌满、让「谁在申诉」这个信息失去意义。
 * 与 {@code /listing_appeal} 同款：无权限门槛但<b>限资源属主</b>，
 * 而 {@code @BotCommand.requiredPermission} 表达不了「资源的属主」，故不声明权限点。
 *
 * <p><b>与 {@code /appeal} 的分工</b>（刻意不合并）：{@code /appeal} 是<b>联邦解封申诉</b>
 * （跨群处罚，收自由文本、无编号，归 tgg-federation）；本命令是<b>审计案件申诉</b>
 * （群内审核命中，必须带案件号）。两者主体与授权源都不同，合并会让联邦申诉被迫要求一个
 * 它可能没有的案件号。
 *
 * <p><b>幂等</b>：同一人对同一案件只允许一条申诉（表上的唯一键 + 先查后写）。
 * 重复提交返回既有回执，不重复落库。
 *
 * <p><b>理由不进日志、不进审核判定</b>：它是用户主动提交的申诉正文，
 * 与 {@code federation_appeals.appeal_text} 同一口径。
 */
@BotCommand(value = "case_appeal", description = "对审核案件提交申诉（限当事人）",
        publicCommand = true, category = MenuCategory.SELF_SERVICE)
public class CaseAppealCommandHandler implements CommandHandler {

    /** 用法文案**不含 {@code <...>}**：模板符号会被整串照抄，命令因而一直回用法。 */
    static final String USAGE = ModerationMessages.CASE_APPEAL_USAGE;

    /** 理由长度上限（与 {@code moderation_case_appeals.reason} 的列宽一致）。 */
    static final int MAX_REASON_CHARS = 500;

    // 文案落点在 {@link ModerationMessages}；这些同名常量保留给测试与既有引用（见 VOICE.md 第八节）。
    static final String NOT_A_GROUP_MEMBER_IDENTITY = ModerationMessages.CASE_APPEAL_NO_IDENTITY;
    static final String CASE_NOT_FOUND_PREFIX = ModerationMessages.CASE_APPEAL_NOT_FOUND_PREFIX;
    static final String NOT_THE_PARTY = ModerationMessages.CASE_APPEAL_NOT_THE_PARTY;
    static final String REASON_TOO_LONG_PREFIX = ModerationMessages.caseAppealReasonTooLong(MAX_REASON_CHARS);

    private final ModerationReviewRepository reviews;
    private final CaseAppealRepository appeals;
    private final Clock clock;

    /** 装配用构造：时钟固定为系统 UTC（与项目其它模块一致，不注册 Clock bean 以免注入歧义）。 */
    @Autowired
    public CaseAppealCommandHandler(ModerationReviewRepository reviews, CaseAppealRepository appeals) {
        this(reviews, appeals, Clock.systemUTC());
    }

    /** 测试用构造：可注入固定时钟。 */
    CaseAppealCommandHandler(ModerationReviewRepository reviews, CaseAppealRepository appeals, Clock clock) {
        this.reviews = reviews;
        this.appeals = appeals;
        this.clock = clock;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        Parsed parsed = Parsed.of(ctx.commandArgs().orElse(null));
        if (parsed == null) {
            return reply(ctx, USAGE);
        }
        if (parsed.reason().length() > MAX_REASON_CHARS) {
            return reply(ctx, REASON_TOO_LONG_PREFIX);
        }
        Long userId = ctx.userId();
        if (userId == null) {
            return reply(ctx, NOT_A_GROUP_MEMBER_IDENTITY);
        }

        Optional<ModerationReviewItem> found = reviews.findById(parsed.caseId());
        if (found.isEmpty()) {
            return reply(ctx, CASE_NOT_FOUND_PREFIX + parsed.caseId() + "。");
        }
        // ★ 安全边界：案件号公开，申诉权不公开。缺了这一条，任何人都能用别人的编号申诉。
        if (!userId.equals(found.get().getUserId())) {
            return reply(ctx, NOT_THE_PARTY);
        }

        Optional<CaseAppeal> existing = appeals.findByReviewIdAndUserId(parsed.caseId(), userId);
        if (existing.isPresent()) {
            return reply(ctx, ModerationMessages.caseAppealDuplicate(parsed.caseId(), existing.get().getId()));
        }

        CaseAppeal saved = appeals.save(
                new CaseAppeal(parsed.caseId(), userId, parsed.reason(), clock.instant()));
        return reply(ctx, ModerationMessages.caseAppealSubmitted(saved.getId(), parsed.caseId()));
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }

    /**
     * 命令操作数：{@code <案件编号> <理由…>}。
     *
     * <p>理由作为**尾部剩余文本**解析（允许含空格）——与 {@code /listing_appeal} 同一取舍：
     * 编号是单个 token（按第一个空白切），理由是人类写的句子（不该被空格切断）。
     * 解析失败（缺参 / 编号非数字 / 理由为空）一律返回 {@code null}，由调用方回用法。
     */
    record Parsed(long caseId, String reason) {

        static Parsed of(String args) {
            if (args == null || args.isBlank()) {
                return null;
            }
            String trimmed = args.trim();
            int sep = indexOfWhitespace(trimmed);
            if (sep < 0) {
                return null;
            }
            long caseId;
            try {
                caseId = Long.parseLong(trimmed.substring(0, sep).trim());
            } catch (NumberFormatException ex) {
                return null;
            }
            String reason = trimmed.substring(sep + 1).trim();
            return reason.isEmpty() ? null : new Parsed(caseId, reason);
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
}

package com.tg.heyisheng.bot.listing.command;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.listing.ListingAppeal;
import com.tg.heyisheng.bot.listing.ListingAppealRepository;
import com.tg.heyisheng.bot.listing.ListingGroup;
import com.tg.heyisheng.bot.listing.ListingGroupRepository;
import com.tg.heyisheng.bot.listing.ListingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * {@code /listing_appeal <收录编号> <理由>} —— 提交失效申诉（设计文档 §6.1）。仅<b>提交者本人</b>。
 *
 * <p><b>为什么需要它</b>：判失效是软删，条目还在库里，但对外已不可见。探针的保守三态只挡掉了
 * 「探测失败」，挡不掉「Telegram 侧数据陈旧 / 群主刚重建了群」这类真误判。没有申诉入口，
 * 误判就是终局——「内部保留软删记录」的设计意图（可审计、可申诉）也只兑现了一半。
 *
 * <p><b>三道判定</b>：
 * <ol>
 *   <li><b>条目存在</b>——编号写错时给出明确提示，而不是静默落一条指向不存在条目的记录；</li>
 *   <li><b>提交者本人</b>——他人（包括管理员）不得代提交，否则申诉队列会被灌水，
 *       且「谁在申诉」这个信息失去意义；</li>
 *   <li><b>在异议期窗口内</b>（{@code tgg.listing.dispute-window-days}，默认 7 天）
 *       ——窗口从 {@code suspendedAt} 起算，过期不再受理（设计文档 §6.1「7 天窗口内」）。</li>
 * </ol>
 *
 * <p><b>不需要「先 SUSPENDED 才可申诉」之外的额外门槛</b>：条目仍在 ACTIVE 时不接受申诉
 * ——没有问题需要申诉；这时给出提示而不是落一条无意义的 PENDING 记录。
 *
 * <p><b>命令名 {@code listing_appeal} 而非文档写的 {@code /listing appeal}</b>：理由同
 * {@code ListingAddCommandHandler} 的 javadoc——命令注册表是「一名一处理一权限」的严格映射，
 * 而库的 {@code Message.getCommand()} 只覆盖第一个词，故三条命令必须是三个互异命令名。
 *
 * <p><b>日志脱敏</b>：申诉正文（{@code commandArgs}）<b>只落库、绝不进日志</b>。
 */
@BotCommand(value = "listing_appeal", description = "对已下架的收录条目提交申诉")
@ConditionalOnProperty(prefix = "tgg.listing", name = "enabled", havingValue = "true")
public class ListingAppealCommandHandler implements CommandHandler {

    static final String USAGE = "用法：/listing_appeal <收录编号> <理由>";
    static final String NOT_SUSPENDED = "该群当前未被下架，无需申诉。";
    static final String NOT_SUBMITTER = "只有该群的提交者本人可以申诉。";

    private final ListingGroupRepository groups;
    private final ListingAppealRepository appeals;
    private final ListingProperties properties;
    private final Clock clock;

    /**
     * 装配用构造：时钟固定为系统 UTC。
     *
     * <p><b>为什么不注入 {@code Clock} bean</b>：整模块刻意不注册 {@code Clock} bean
     * （避免与其它模块的 {@code Clock} 造成按类型注入歧义，见 {@code ListingConfiguration}）。
     * 本类若声明一个 {@code Clock} 构造参数，扫描装配时就会因「无 Clock bean」直接启动失败——
     * 这与 {@code ListingGroupService} 的处理方式一致：默认系统时钟，测试经包级构造注入。
     */
    @Autowired
    public ListingAppealCommandHandler(ListingGroupRepository groups,
                                       ListingAppealRepository appeals,
                                       ListingProperties properties) {
        this(groups, appeals, properties, Clock.systemUTC());
    }

    /** 测试用构造：可注入固定时钟以断言异议期边界。 */
    ListingAppealCommandHandler(ListingGroupRepository groups,
                                ListingAppealRepository appeals,
                                ListingProperties properties,
                                Clock clock) {
        this.groups = groups;
        this.appeals = appeals;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        String args = ctx.commandArgs().orElse(null);
        Long listingId = listingIdOf(args);
        String reason = reasonOf(args);
        if (listingId == null || reason == null) {
            return reply(ctx, USAGE);
        }

        Optional<ListingGroup> found = groups.findById(listingId);
        if (found.isEmpty()) {
            return reply(ctx, "未找到收录编号 " + listingId + "。");
        }
        ListingGroup entry = found.get();

        if (ctx.userId() == null || !ctx.userId().equals(entry.getSubmitterUserId())) {
            return reply(ctx, NOT_SUBMITTER);
        }
        if (!ListingGroup.Status.SUSPENDED.name().equals(entry.getStatus())) {
            return reply(ctx, NOT_SUSPENDED);
        }
        if (isDisputeWindowClosed(entry)) {
            return reply(ctx, "异议期（" + properties.getDisputeWindowDays() + " 天）已过，无法申诉。");
        }

        ListingAppeal saved = appeals.save(
                new ListingAppeal(entry.getId(), ctx.userId(), reason, clock.instant()));
        return reply(ctx, "申诉已提交（编号 #" + saved.getId() + "），将由管理员审核。");
    }

    /** 窗口从下架时刻起算；{@code suspendedAt} 缺失（异常数据）时<b>不</b>阻断申诉——宁可多收一条也不误拒。 */
    private boolean isDisputeWindowClosed(ListingGroup entry) {
        if (entry.getSuspendedAt() == null) {
            return false;
        }
        return clock.instant().isAfter(
                entry.getSuspendedAt().plus(properties.getDisputeWindowDays(), ChronoUnit.DAYS));
    }

    /** 取第一个空白之前的编号；它不是命令名解析（命令名由 {@code Message.getCommand()} 给出）。 */
    private static Long listingIdOf(String args) {
        if (args == null) {
            return null;
        }
        String head = head(args);
        if (head == null) {
            return null;
        }
        try {
            return Long.valueOf(head);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 取第一个空白之后的理由（原样保留，不做 trim 之外的处理）。 */
    private static String reasonOf(String args) {
        if (args == null) {
            return null;
        }
        int sep = indexOfWhitespace(args);
        if (sep < 0) {
            return null;
        }
        String reason = args.substring(sep + 1).trim();
        return reason.isEmpty() ? null : reason;
    }

    private static String head(String args) {
        int sep = indexOfWhitespace(args);
        String head = (sep < 0 ? args : args.substring(0, sep)).trim();
        return head.isEmpty() ? null : head;
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

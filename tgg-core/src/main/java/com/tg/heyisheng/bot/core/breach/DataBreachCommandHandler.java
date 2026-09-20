package com.tg.heyisheng.bot.core.breach;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.Confirm;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;
import java.util.Locale;

/**
 * {@code /data_breach} —— 数据泄露登记与通报记录（模块十 §11.2）。需为平台白名单成员。
 *
 * <p>三种用法：{@code /data_breach <影响范围> <人数>}（登记，72h 开始计时）、
 * {@code /data_breach report <编号>}（记录已通报）、{@code /data_breach}（列出未通报）。
 *
 * <p><b>权限走全局白名单</b>（复用 {@link ModerationReviewGuard}）：泄露是平台级事故，
 * 与群内管理员无关；非白名单成员**静默**（回复「权限不足」等于确认了该命令存在）。
 *
 * <p><b>它不代替运营者判断</b>：是否构成「需通报的泄露」是法律判断，本命令只负责计时与留痕。
 */
@BotCommand(value = "data_breach", description = "数据泄露登记与 72h 通报记录（平台白名单）",
        confirm = Confirm.WHEN_ARGS, category = MenuCategory.REVIEW)
public class DataBreachCommandHandler implements CommandHandler {

    static final String USAGE = "用法：\n"
            + "/data_breach <影响范围> <影响人数> —— 登记一起泄露事件（72 小时计时开始）\n"
            + "/data_breach report <编号> —— 记录已履行通报\n"
            + "/data_breach —— 列出尚未通报的事件\n"
            + "注：通报是运营者的法定义务，本命令只做计时与留痕。";

    private final DataBreachService service;
    private final ModerationReviewGuard guard;

    public DataBreachCommandHandler(DataBreachService service, ModerationReviewGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        if (!guard.isReviewer(ctx.userId())) {
            return null;
        }
        String args = ctx.commandArgs().orElse(null);
        if (args == null || args.isBlank()) {
            return reply(ctx, render(service.pending()));
        }

        String trimmed = args.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("report ")) {
            return reply(ctx, report(ctx, trimmed.substring("report ".length()).trim()));
        }
        return reply(ctx, register(ctx, trimmed));
    }

    private String register(UpdateContext ctx, String args) {
        String[] parts = args.split("\\s+");
        if (parts.length < 2) {
            return USAGE;
        }
        int count;
        try {
            count = Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException ex) {
            return USAGE;
        }
        // 范围描述可含空格（除最后一段的人数），故先切出人数再拼回范围
        String scope = args.substring(0, args.length() - parts[parts.length - 1].length()).trim();
        try {
            DataBreachIncident incident = service.register(null, scope, count, ctx.userId());
            return "已登记泄露事件 #" + incident.getId() + "，通报截止 " + incident.getDeadlineAt()
                    + "（72 小时内）。履行通报后用 /data_breach report " + incident.getId() + " 记录。";
        } catch (TggException ex) {
            return "登记未成功：" + ex.getMessage();
        }
    }

    private String report(UpdateContext ctx, String idText) {
        long id;
        try {
            id = Long.parseLong(idText);
        } catch (NumberFormatException ex) {
            return USAGE;
        }
        return service.markReported(id, ctx.userId())
                ? "已记录泄露事件 #" + id + " 的通报。"
                : "事件 #" + id + " 不存在，或已记录过通报（不覆盖首次通报时间——那是合规证据）。";
    }

    private static String render(List<DataBreachIncident> pending) {
        if (pending.isEmpty()) {
            return "当前没有未通报的数据泄露事件。";
        }
        StringBuilder sb = new StringBuilder("尚未通报的数据泄露事件：\n");
        for (DataBreachIncident incident : pending) {
            sb.append("#").append(incident.getId())
                    .append(" · 发现 ").append(incident.getDetectedAt())
                    .append(" · 截止 ").append(incident.getDeadlineAt())
                    .append(" · 影响约 ").append(incident.getAffectedCount()).append(" 人\n")
                    .append("   范围：").append(incident.getScope()).append('\n');
        }
        sb.append("履行通报后：/data_breach report <编号>");
        return sb.toString();
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

package com.tg.heyisheng.bot.core.notify;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * {@code /quiet_hours} —— 设置本人的免打扰时段（模块十 §11.1）。
 *
 * <p><b>自助命令，不需要权限</b>：免打扰是<b>个人偏好</b>，不是群体治理动作——
 * 要求 {@code MANAGE_CONFIG} 之类权限等于让用户求管理员设自己的静音时段。
 *
 * <p>三种用法：{@code /quiet_hours 22:00-08:00}（设置）｜{@code /quiet_hours off}（清除）｜
 * {@code /quiet_hours}（查看）。
 *
 * <p><b>紧急通知不受免打扰影响</b>——回复里必须把这一点说清楚，否则用户会以为「静音 = 什么都收不到」，
 * 而封禁/解封恰恰是他们最需要立刻知道的。
 */
@BotCommand(value = "quiet_hours", description = "设置免打扰时段，例：/quiet_hours 22:00-08:00",
        publicCommand = true)
public class QuietHoursCommandHandler implements CommandHandler {

    static final String USAGE = "用法：\n"
            + "/quiet_hours 22:00-08:00 —— 设置免打扰时段（支持跨午夜）\n"
            + "/quiet_hours off —— 取消免打扰\n"
            + "/quiet_hours —— 查看当前设置\n"
            + "注：封禁、解封等**紧急**通知不受免打扰影响，始终送达。";

    private final NotificationPreferenceService preferences;

    public QuietHoursCommandHandler(NotificationPreferenceService preferences) {
        this.preferences = preferences;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        Long userId = ctx.userId();
        if (userId == null) {
            return reply(ctx, USAGE);
        }
        String args = ctx.commandArgs().orElse(null);

        if (args == null || args.isBlank()) {
            QuietHours current = preferences.quietHoursOf(userId);
            return reply(ctx, current == null
                    ? "你当前没有设置免打扰时段（所有通知都会送达）。"
                    : "你当前的免打扰时段：" + format(current) + "（紧急通知不受影响）。");
        }

        if ("off".equals(args.trim().toLowerCase(Locale.ROOT))) {
            boolean cleared = preferences.clearQuietHours(userId);
            return reply(ctx, cleared ? "已取消免打扰时段。" : "你本来就没有设置免打扰时段。");
        }

        QuietHours parsed = parse(args.trim());
        if (parsed == null) {
            return reply(ctx, "时段格式不对。\n" + USAGE);
        }
        preferences.setQuietHours(userId, parsed);
        return reply(ctx, "已设置免打扰时段：" + format(parsed)
                + "。\n该时段内的普通/重要通知会延后到时段结束后发送；紧急通知（封禁、解封等）不受影响。");
    }

    /** 解析 {@code HH:mm-HH:mm}；格式非法返回 {@code null}（命令层给可读提示，不抛异常）。 */
    static QuietHours parse(String text) {
        String[] parts = text.split("-", 2);
        if (parts.length < 2) {
            return null;
        }
        try {
            LocalTime start = LocalTime.parse(parts[0].trim());
            LocalTime end = LocalTime.parse(parts[1].trim());
            return new QuietHours(start, end);
        } catch (DateTimeParseException | IllegalArgumentException ex) {
            // 含起止相同（QuietHours 构造器拒绝）——那不是一个合法区间
            return null;
        }
    }

    private static String format(QuietHours hours) {
        return hours.start() + "-" + hours.end();
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

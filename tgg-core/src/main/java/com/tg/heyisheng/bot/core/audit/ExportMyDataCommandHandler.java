package com.tg.heyisheng.bot.core.audit;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.notify.NotificationPreferenceService;
import com.tg.heyisheng.bot.core.notify.QuietHours;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;

/**
 * {@code /export_my_data} —— 导出机器人持有的<b>关于你本人</b>的数据（模块十 §11.2 数据导出；
 * 对应 GDPR 的数据主体访问权）。
 *
 * <p><b>自助命令，不需要权限</b>：导出的是他自己的数据，凭什么要别人批准。
 *
 * <p><b>只查 {@code actor = 自己}</b>：这是本命令唯一的安全边界，也是最容易写错的地方
 * ——一次「查全部再过滤」的写法就会把别人的审计记录发给他。故查询条件直接带 actor，
 * 并有专门的测试断言「查不到他人的条目」。
 *
 * <p><b>不含消息正文</b>：审计表本就不存正文（`detail` 只放结论），故导出天然安全。
 */
@BotCommand(value = "export_my_data", description = "导出机器人持有的关于你的数据",
        publicCommand = true)
public class ExportMyDataCommandHandler implements CommandHandler {

    /** Telegram 单条消息硬上限（超长整条发送失败）。 */
    static final int TELEGRAM_TEXT_LIMIT = 4096;
    /** 为「已截断」提示预留的余量。 */
    private static final int TRUNCATION_RESERVE = 60;

    private final AuditLogRepository auditLogRepository;
    private final NotificationPreferenceService preferences;

    public ExportMyDataCommandHandler(AuditLogRepository auditLogRepository,
                                      NotificationPreferenceService preferences) {
        this.auditLogRepository = auditLogRepository;
        this.preferences = preferences;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        Long userId = ctx.userId();
        if (userId == null) {
            return reply(ctx, "无法识别你的用户身份，请稍后再试。");
        }

        StringBuilder sb = new StringBuilder("机器人持有的关于你的数据：\n");
        QuietHours quiet = preferences.quietHoursOf(userId);
        sb.append("\n【通知偏好】\n")
                .append(quiet == null ? "未设置免打扰时段" : "免打扰时段：" + quiet.start() + "-" + quiet.end())
                .append('\n');

        List<AuditEntry> entries = auditLogRepository.findByActorIdOrderByIdDesc(userId);
        sb.append("\n【操作审计】共 ").append(entries.size()).append(" 条\n");
        int shown = 0;
        for (AuditEntry entry : entries) {
            String line = "· " + entry.getOccurredAt() + " " + entry.getAction()
                    + " [" + entry.getOutcome() + "]\n";
            if (sb.length() + line.length() > TELEGRAM_TEXT_LIMIT - TRUNCATION_RESERVE) {
                sb.append("…（已截断，仅显示最早 ").append(shown).append(" 条）\n");
                break;
            }
            sb.append(line);
            shown++;
        }
        sb.append("\n注：机器人不存储消息正文；审计只记录「谁在何时做了什么、结果如何」。");
        return reply(ctx, sb.toString());
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

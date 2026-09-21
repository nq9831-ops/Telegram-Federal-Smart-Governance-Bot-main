package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.permission.Permission;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;

/**
 * {@code /rules} —— 查看本群的教学规则。需 {@link Permission#MANAGE_CONFIG}。
 *
 * <p><b>为什么需要它</b>：{@code /teach} 落库之后若没有查看入口，管理员无从知道「本群现在有哪些规则、
 * 谁在什么时候教的」——规则的可见性是它可被治理的前提（否则只能改库排查）。
 *
 * <p><b>为什么门槛是 MANAGE_CONFIG 而不是全员</b>：规则里写着检测什么模式，
 * 对骗子而言这是一份「怎么绕过」的说明书。与 {@code /words}（违禁词表）同款口径。
 *
 * <p><b>必须截断</b>：Telegram 单条消息上限 4096 字符，超长会<b>整条发送失败</b>——
 * 回复直接丢掉，调用方还以为命令成功了（本项目既有教训），见 {@code /words} 的同款处理。
 */
@BotCommand(value = "rules", description = "查看本群教学规则（需 MANAGE_CONFIG）",
        requiredPermission = Permission.MANAGE_CONFIG, category = MenuCategory.MODERATION)
public class TaughtRulesCommandHandler implements CommandHandler {

    /** Telegram 单条消息硬上限（超长整条失败）。 */
    static final int TELEGRAM_TEXT_LIMIT = 4096;
    /** 正文自留上限：给截断提示留余量。 */
    static final int MAX_BODY_CHARS = 3500;

    static final String EMPTY = WordFilterMessages.RULES_EMPTY;
    static final String PREFIX = WordFilterMessages.RULES_PREFIX;

    private final TaughtRuleService service;

    public TaughtRulesCommandHandler(TaughtRuleService service) {
        this.service = service;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        Long chatId = ctx.chatId();
        if (chatId == null) {
            return reply(ctx, WordFilterMessages.RULES_NO_CHAT);
        }
        return reply(ctx, render(service.listTaught(chatId)));
    }

    /** 渲染列表：逐条追加，越过 {@link #MAX_BODY_CHARS} 就停手并说明被截断。 */
    static String render(List<TaughtRule> rules) {
        if (rules.isEmpty()) {
            return EMPTY;
        }
        StringBuilder sb = new StringBuilder(PREFIX);
        int shown = 0;
        for (TaughtRule rule : rules) {
            String line = "\n" + (rule.isEnabled()
                    ? WordFilterMessages.RULE_ENABLED_MARK : WordFilterMessages.RULE_DISABLED_MARK)
                    + rule.getRuleId() + " · " + rule.getRiskLevel() + " · " + rule.getName();
            if (sb.length() + line.length() > MAX_BODY_CHARS) {
                break;
            }
            sb.append(line);
            shown++;
        }
        if (shown < rules.size()) {
            sb.append(WordFilterMessages.rulesTruncated(rules.size(), shown));
        }
        return sb.toString();
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

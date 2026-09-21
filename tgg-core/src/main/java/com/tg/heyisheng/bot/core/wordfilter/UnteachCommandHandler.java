package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.permission.Permission;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * {@code /unteach <规则id>} —— 停用一条本群教学规则。需 {@link Permission#TEACH_RULE}。
 *
 * <p><b>教错了必须能撤</b>：没有撤销入口的话，一条误伤的正则只能靠改库下线——
 * 而它此刻正在本群热路径上生效。这是 {@code /teach} 的必要配套。
 *
 * <p><b>停用而非删除</b>：「谁在什么时候教过什么、什么时候停的」是审计对象
 * （与项目既有的软删纪律一致）；停用后规则不再参与检测。
 */
@BotCommand(value = "unteach", description = "停用一条本群教学规则（需 TEACH_RULE）",
        requiredPermission = Permission.TEACH_RULE, category = MenuCategory.MODERATION)
public class UnteachCommandHandler implements CommandHandler {

    static final String USAGE = "用法：/unteach 规则id（先 /taught_rules 查看本群规则 id）";

    private final TaughtRuleService service;

    public UnteachCommandHandler(TaughtRuleService service) {
        this.service = service;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        String ruleId = ctx.commandArgs().orElse(null);
        Long chatId = ctx.chatId();
        if (ruleId == null || ruleId.isBlank() || chatId == null) {
            return reply(ctx, USAGE);
        }
        boolean disabled = service.disable(chatId, ruleId.trim());
        return reply(ctx, disabled
                ? "规则 " + ruleId.trim() + " 已停用（立即生效）。"
                : "本群没有规则 " + ruleId.trim() + "。");
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

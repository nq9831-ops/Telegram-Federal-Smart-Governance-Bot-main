package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import com.tg.heyisheng.bot.core.permission.Permission;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

/**
 * {@code /teach <规则id> <正则> <描述>} —— 教一条<b>本群</b>审核规则（V5.0 §10.3）。需 {@link Permission#TEACH_RULE}。
 *
 * <p><b>它填补的空洞</b>：模块九的规则此前只在代码里（{@code BuiltInRules}），
 * 「管理员发现新骗局 → 让它生效」这条路不存在。本命令让规则进库（V8 表）并<b>立即对本群生效</b>。
 *
 * <p><b>为什么只能教非硬红线</b>：{@code hardLine=true} 的规则命中即<b>跳过人工复核、直接删除+封禁</b>。
 * 把这个能力交给命令层自由使用，等于让一个管理员账号能凭一条正则即时封人。
 * 硬红线因此**只能由代码内置**（{@code BuiltInRules}），本命令固定 MEDIUM + 非硬红线
 * —— 教出来的规则一律「进人工复核」，与人审兜底的设计一致。
 *
 * <p><b>V5.0 §10.3 的差异（刻意）</b>：规格里「DeepSeek 解析描述 → 自动生成正则 → 管理员确认」，
 * 本实现改为<b>管理员直接写正则</b>，并把命令执行本身当作「确认」（回复里回显规则预览）。
 * 理由：自动生成需要一个能验证的模型链路（本机无），而「写进仓库却验不了的代码」是本项目反复警惕的；
 * 自动生成属接入位，与 L2/L4 的处理方式一致。
 *
 * <p><b>正则不得含空白</b>（命令参数以空白分隔）——含空格的正则请用 {@code \s} 等转义表达。
 */
@BotCommand(value = "teach", description = "教一条本群审核规则（需 TEACH_RULE）",
        requiredPermission = Permission.TEACH_RULE)
public class TeachCommandHandler implements CommandHandler {

    static final String USAGE = "用法：/teach <规则id> <正则> <描述>\n例：/teach SCAM_AIRDROP \"免费空投\\\\d+\" 假空投骗局";
    static final String NOT_A_GROUP = "请在要生效的群内执行本命令。";

    private final TaughtRuleService service;
    /** 教学门槛（§10.3）；默认放行——未接线时行为与升级前逐字一致。 */
    private final TeachEligibility eligibility;

    /** 兼容构造：不设门槛（既有调用方与测试用）。 */
    public TeachCommandHandler(TaughtRuleService service) {
        this(service, TeachEligibility.allowAll());
    }

    @Autowired
    public TeachCommandHandler(TaughtRuleService service, TeachEligibility eligibility) {
        this.service = service;
        this.eligibility = eligibility == null ? TeachEligibility.allowAll() : eligibility;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        String args = ctx.commandArgs().orElse(null);
        if (args == null || args.isBlank()) {
            return reply(ctx, USAGE);
        }
        // 描述可含空格（取剩余全部）；正则与规则 id 不行——因此切成 3 段而不是按空白全切
        String[] parts = args.trim().split("\\s+", 3);
        if (parts.length < 3) {
            return reply(ctx, USAGE);
        }
        Long chatId = ctx.chatId();
        if (chatId == null || chatId >= 0) {
            return reply(ctx, NOT_A_GROUP);
        }

        String ruleId = parts[0];
        String regex = parts[1];
        String name = parts[2];

        // 门槛先于落库：拒绝时**不得**留下已生效的规则——否则管理员看到失败提示、规则却在群里跑。
        Optional<String> rejection = eligibility.rejectionFor(chatId, ctx.userId());
        if (rejection.isPresent()) {
            return reply(ctx, "规则未生效：" + rejection.get());
        }

        try {
            TaughtRule saved = service.teach(chatId, ruleId, name, regex, RiskLevel.MEDIUM, false, ctx.userId());
            // 回显预览即「确认」步骤：让管理员看到真正落库的正则（含转义后的形态）
            return reply(ctx, "规则已生效（本群立即起效）：\n"
                    + "编号：" + saved.getRuleId() + "\n"
                    + "正则：" + saved.getRegex() + "\n"
                    + "描述：" + saved.getName() + "\n"
                    + "命中后：判为 MEDIUM，进入人工复核（不会自动封禁）。");
        } catch (TggException ex) {
            // 校验失败是管理员的输入问题，如实回显原因（不回显正文、不吞异常）
            return reply(ctx, "规则未生效：" + ex.getMessage());
        }
    }

    private static SendMessage reply(UpdateContext ctx, String text) {
        return SendMessage.builder().chatId(String.valueOf(ctx.chatId())).text(text).build();
    }
}

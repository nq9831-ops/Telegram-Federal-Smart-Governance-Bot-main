package com.tg.heyisheng.bot.core.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 启动期把命令清单注册到 Telegram —— 即客户端里输入 {@code /} 时弹出的命令菜单。
 *
 * <p><b>为什么必须做</b>：{@code @BotCommand.description()} 自切片 1 起就写着「暂未使用」，
 * 命令清单从未注册到 Telegram。真实环境实测（2026-09-19 · 公网 bot）：{@code getMyCommands}
 * 返回 **0 条**，于是 29 个命令在客户端里毫无提示——用户只能靠背。
 *
 * <p><b>为什么要在本地过滤</b>：Telegram 的 {@code setMyCommands} 是**全量替换**，
 * 一个不合法的条目会让整个请求失败、菜单保持原样（表现为「注册了但一条都没生效」）。
 * 故先按 Bot API 的硬约束剔条目，再一次性发送。
 *
 * <p>失败**不影响启动**：注册菜单是锦上添花，不该让整个进程起不来；异常只记日志。
 */
public class CommandMenuRegistrar {

    private static final Logger log = LoggerFactory.getLogger(CommandMenuRegistrar.class);

    /** Bot API 对描述长度的硬上限。 */
    static final int MAX_DESCRIPTION = 256;

    /** Bot API 对命令名的硬约束：1–32 个 {@code [a-z0-9_]}。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_]{1,32}");

    private final Map<String, String> commands;
    /** 发送通道；为 {@code null} 表示未配置 bot token（跳过注册）。 */
    private final Consumer<BotApiMethod<?>> sender;

    public CommandMenuRegistrar(Map<String, String> commands, Consumer<BotApiMethod<?>> sender) {
        this.commands = Map.copyOf(commands);
        this.sender = sender;
    }

    /**
     * 组装并注册命令菜单。
     *
     * @return 实际注册进的命令名（按菜单顺序）；未注册时为空列表
     */
    public List<String> register() {
        if (sender == null) {
            log.warn("未配置 TGG_BOT_TOKEN：跳过命令菜单注册——客户端里输入 / 不会出现任何命令提示。"
                    + "注入 token 后重启即自动注册。");
            return List.of();
        }

        List<BotCommand> menu = toMenuCommands(commands);
        if (menu.isEmpty()) {
            // 空列表在 Bot API 里是合法请求——它会**清空**既有菜单，那不是这里想要的语义
            log.warn("命令菜单为空（所有命令都缺描述或名字非法），本次不发送以免清空既有菜单");
            return List.of();
        }

        List<String> names = menu.stream().map(BotCommand::getCommand).toList();
        try {
            sender.accept(new SetMyCommands(menu));
            log.info("已注册命令菜单 {} 条：{}", names.size(), String.join(" ", names));
            return names;
        } catch (RuntimeException ex) {
            // 菜单注册失败不该影响服务可用性
            log.warn("注册命令菜单失败（不影响命令本身可用）：{}", ex.toString());
            return List.of();
        }
    }

    /**
     * 把「命令名 → 描述」过滤成 Telegram 可接受的菜单项。
     *
     * <p>输出**按命令名排序**：{@code Map.copyOf} 与 {@code HashMap} 都不保证迭代顺序，
     * 不排序会让菜单与日志每次抖动（测试也会变成随机绿/随机红）。
     *
     * <p>剔除两类条目（都会让整批注册失败，或渲染成看不懂的空行）：
     * 名字不合 Bot API 约束的命令、以及**未写描述**的命令——
     * 后者刻意剔除而非填空串，是为了让「漏写描述」在日志里显形，而不是在客户端里变成一个空壳菜单项。
     */
    public static List<BotCommand> toMenuCommands(Map<String, String> commands) {
        List<BotCommand> menu = new ArrayList<>();
        for (Map.Entry<String, String> entry : commands.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            String description = entry.getValue() == null ? "" : entry.getValue().trim();

            if (!NAME_PATTERN.matcher(name).matches()) {
                log.warn("命令名不符合 Telegram 约束（[a-z0-9_]{1,32}），未进菜单：{}", name);
                continue;
            }
            if (description.isEmpty()) {
                log.warn("命令 {} 未写描述，未进菜单——补上 @BotCommand(description=...) 后自动出现", name);
                continue;
            }
            if (description.length() > MAX_DESCRIPTION) {
                description = description.substring(0, MAX_DESCRIPTION);
            }
            menu.add(BotCommand.builder().command(name).description(description).build());
        }
        menu.sort(Comparator.comparing(BotCommand::getCommand));
        return menu;
    }
}

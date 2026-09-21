package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.RoleGrant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScope;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChatMember;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 启动期把**客户端 {@code /} 提示菜单**注册到 Telegram。
 *
 * <p><b>客户端菜单已收敛为单一入口</b>：只注册显式声明 {@code clientMenu = true} 的命令
 * （当前即 {@code /menu}）。这与项目「从背命令到点面板」的方向一致——普通成员不再在 {@code /}
 * 里面对一长串（有的还带管理字样）的命令清单，而是从 {@code /menu} 面板按自身权限点用。
 *
 * <p><b>为什么不给每个人不同的菜单</b>：早先的实现按权限分档——管理类命令进
 * {@code BotCommandScopeChatMember}、公开类进默认档。但那要求为**每条授权**维护一份 per-user
 * 菜单，且给不出「全局按人」的档（Telegram 没有这种 scope）。现在客户端菜单人人一份、内容相同，
 * 按权限呈现的职责整体让给了 {@code /menu} 面板的可见性接缝。
 *
 * <p><b>逐成员档为什么还在</b>：Telegram 的 scope <b>只写不可枚举</b>——无法问它「列出所有
 * per-user 菜单」。旧版留下的 {@code BotCommandScopeChatMember} 残留若不覆盖，那些用户仍会看到
 * 旧菜单。故对**当前配置的每条授权**用与默认档相同的内容覆盖一次；配置里已删除的旧授权其残留
 * 无法枚举，属已知边界。
 *
 * <p><b>为什么在本地过滤</b>：Telegram 的 {@code setMyCommands} 是**全量替换**（按 scope 各算一份），
 * 一个不合法的条目会让整个请求失败、菜单保持原样（表现为「注册了但一条都没生效」）。
 * 故先按 Bot API 的硬约束剔条目，再逐档发送。
 *
 * <p><b>失败不影响启动</b>：注册菜单是锦上添花，不该让整个进程起不来；异常只记日志。
 * 且**逐档隔离**——某档失败（如 bot 不是该群管理员、目标用户已退群）不影响其余档。
 */
public class CommandMenuRegistrar {

    private static final Logger log = LoggerFactory.getLogger(CommandMenuRegistrar.class);

    /** Bot API 对描述长度的硬上限。 */
    static final int MAX_DESCRIPTION = 256;

    /** Bot API 对命令名的硬约束：1–32 个 {@code [a-z0-9_]}。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_]{1,32}");

    /**
     * 一档菜单：某个 scope 下要注册的命令。
     *
     * @param scope    Telegram 的 scope（默认档 / 逐成员档…）
     * @param label    仅用于日志与断言（如 {@code default} / {@code member:-100:42}）
     * @param commands 该档要注册的命令（已清洗、已排序）
     */
    public record ScopedMenu(BotCommandScope scope, String label, List<BotCommand> commands) {
    }

    /**
     * 发送一个 Bot API 方法的通道。
     *
     * <p><b>为什么返回 {@code boolean}、而不是收一个 {@code Consumer}</b>：{@code Consumer} 会把发送方
     * （{@code TelegramApiMethodExecutor#execute}）的成败**静默丢弃**——那个实现捕获所有异常后
     * {@code return false}，从不抛出；而方法引用赋给 {@code Consumer} 时返回值被编译器默默丢掉。
     * 结果是「逐档失败隔离」退化成一段**永不执行**的代码，调用方还照样打出「已注册」的成功日志。
     * 让接口返回布尔值，等于用类型系统挡住这个错误。
     *
     * @return 仅当**确实发送成功**（HTTP 2xx）时为 {@code true}
     */
    @FunctionalInterface
    public interface Sender {
        boolean send(BotApiMethod<?> method);
    }

    private final List<ScopedMenu> menus;
    /** 发送通道；为 {@code null} 表示未配置 bot token（跳过注册）。 */
    private final Sender sender;

    public CommandMenuRegistrar(List<ScopedMenu> menus, Sender sender) {
        this.menus = List.copyOf(menus);
        this.sender = sender;
    }

    /**
     * 组装客户端菜单（**纯函数**，便于单测）。
     *
     * <p><b>内容</b>：客户端 {@code /} 菜单只含**显式声明** {@code clientMenu = true} 的命令
     * （当前即 {@code /menu}）。其余命令一律经 {@code /menu} 面板按权限呈现。
     *
     * <p><b>为什么仍有「逐成员档」</b>：客户端菜单现已全局统一，本不需要 per-user 档；保留它是为了
     * **覆盖旧版按权限分档时留下的 per-user 菜单**——Telegram 的 scope 只写不可枚举，不覆盖则老用户
     * 仍会看到旧菜单。故对每条授权用**与默认档相同的内容**再发一次。
     *
     * @param registry     命令注册表（提供主命令、描述与所需权限）
     * @param seamCommands 被可见性接缝认领的命令名（即平台白名单类，不进客户端菜单）
     * @param grants       已授权项（{@code tgg.permission.admins} 解析所得；用于覆盖旧版残留）
     * @return 至少含一个默认档；每条授权再生成一个内容相同的逐成员档
     */
    public static List<ScopedMenu> planMenus(CommandRegistry registry,
                                             Set<String> seamCommands,
                                             List<RoleGrant> grants) {
        Map<String, String> clientSpec = new LinkedHashMap<>();
        List<String> unreachable = new ArrayList<>();
        for (Map.Entry<String, String> entry : registry.mainCommands().entrySet()) {
            String name = entry.getKey();
            if (registry.clientMenu(name)) {
                if (seamCommands.contains(name)) {
                    // 同时声明两者是配置矛盾：接缝类的授权是平台白名单、与群无关，
                    // 进客户端菜单等于广播给所有人。fail-closed：不进，并告警。
                    log.warn("命令 {} 同时声明了 clientMenu 与可见性接缝——接缝类不进客户端菜单", name);
                    continue;
                }
                if (registry.requiredPermission(name) != Permission.NONE) {
                    // 带权限点的命令进客户端菜单会向无权者暴露其存在。fail-closed：不进，并告警。
                    log.warn("命令 {} 声明了 clientMenu 但带权限点（{}）——不进客户端菜单",
                            name, registry.requiredPermission(name));
                    continue;
                }
                clientSpec.put(name, entry.getValue());
                continue;
            }
            // 不进客户端菜单的命令：必须还能从 /menu 面板到达，否则对谁都不可见（静默失踪）。
            // 三种可达途径：被接缝认领 / 有权限点（走 RBAC 进面板）/ 声明 publicCommand（自助命令进面板）。
            boolean reachable = seamCommands.contains(name)
                    || registry.requiredPermission(name) != Permission.NONE
                    || registry.publicCommand(name);
            if (!reachable) {
                unreachable.add(name);
            }
        }
        if (!unreachable.isEmpty()) {
            log.warn("以下命令既不在客户端菜单、也无法经 /menu 面板到达（无权限点、未被可见性接缝认领、"
                            + "又未声明 publicCommand）：{}"
                            + "——若应经面板呈现请补 @BotCommand(publicCommand = true)；"
                            + "若是平台门控命令，请为其模块注册 MenuVisibility 接缝。",
                    String.join(", ", unreachable));
        }

        List<BotCommand> clientCommands = toMenuCommands(clientSpec);
        List<ScopedMenu> planned = new ArrayList<>();
        planned.add(new ScopedMenu(new BotCommandScopeDefault(), "default", clientCommands));
        for (RoleGrant grant : grants) {
            // 与默认档**同内容**：目的只是覆盖旧版遗留的 per-user 菜单（见方法 javadoc），不是再分档。
            planned.add(new ScopedMenu(
                    new BotCommandScopeChatMember(String.valueOf(grant.chatId()), grant.userId()),
                    "member:" + grant.chatId() + ":" + grant.userId(),
                    clientCommands));
        }
        return List.copyOf(planned);
    }

    /**
     * 逐档注册。
     *
     * @return 实际注册成功的档位标签（供日志与测试）；未配置 token 时为空列表
     */
    public List<String> register() {
        if (sender == null) {
            log.warn("未配置 TGG_BOT_TOKEN：跳过命令菜单注册——客户端里输入 / 不会出现任何命令提示。"
                    + "注入 token 后重启即自动注册。");
            return List.of();
        }
        if (menus.isEmpty()) {
            log.warn("命令菜单分档为空（无任何可注册的命令），本次不发送以免清空既有菜单");
            return List.of();
        }

        List<String> registered = new ArrayList<>();
        int failed = 0;
        for (ScopedMenu menu : menus) {
            if (menu.commands().isEmpty()) {
                // 空列表在 Bot API 里是合法请求——它会**清空**该 scope 的既有菜单，那不是这里想要的语义
                log.warn("跳过空档 {}（空列表会清空该 scope 的既有菜单）", menu.label());
                continue;
            }
            boolean ok;
            try {
                ok = sender.send(SetMyCommands.builder()
                        .commands(menu.commands())
                        .scope(menu.scope())
                        .build());
            } catch (RuntimeException ex) {
                // 兜底：sender 实现若选择**抛出**（而非返回 false）也照样逐档隔离
                failed++;
                log.warn("注册命令菜单档 {} 抛异常（该档用户将回落到默认档）：{}", menu.label(), ex.toString());
                continue;
            }
            if (!ok) {
                // 真实失败路径：HTTP 非 2xx（bot 非该群管理员、目标用户已退群…）由返回值为 false 表达
                failed++;
                log.warn("注册命令菜单档 {} 未成功（该档用户将回落到默认档）", menu.label());
                continue;
            }
            registered.add(menu.label());
            log.info("已注册命令菜单档 {}：{} 条", menu.label(), menu.commands().size());
        }
        if (failed > 0) {
            log.warn("命令菜单有 {} 档注册失败——这些用户会回落到默认档（只含公开命令，不会泄露管理命令）",
                    failed);
        }
        return List.copyOf(registered);
    }

    /**
     * 把「命令名 → 描述」过滤成 Telegram 可接受的菜单项。
     *
     * <p>输出**按命令名排序**：{@code Map.copyOf} 与 {@code HashMap} 都不保证迭代顺序，
     * 不排序会让菜单与日志每次抖动（测试也会变成随机绿/随机红）。
     *
     * <p>描述先经 {@link CommandDescriptions#stripPermissionNote} 清洗——菜单已按权限分档，
     * 看到它的人必然有权限，再带「（需管理员权限）」是噪声（{@code /menu} 的按钮早已这么做，
     * 两个入口的文案必须一致）。
     *
     * <p>剔除两类条目（都会让整批注册失败，或渲染成看不懂的空行）：
     * 名字不合 Bot API 约束的命令、以及**清洗后描述为空**的命令——
     * 后者刻意剔除而非填空串，是为了让「漏写描述」在日志里显形，而不是在客户端里变成一个空壳菜单项。
     */
    public static List<BotCommand> toMenuCommands(Map<String, String> commands) {
        List<BotCommand> menu = new ArrayList<>();
        for (Map.Entry<String, String> entry : commands.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            String description = CommandDescriptions.stripPermissionNote(entry.getValue());

            if (!NAME_PATTERN.matcher(name).matches()) {
                log.warn("命令名不符合 Telegram 约束（[a-z0-9_]{1,32}），未进菜单：{}", name);
                continue;
            }
            if (description.isEmpty()) {
                log.warn("命令 {} 的描述为空（或只剩权限括注），未进菜单"
                        + "——补上 @BotCommand(description = ...) 后自动出现", name);
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

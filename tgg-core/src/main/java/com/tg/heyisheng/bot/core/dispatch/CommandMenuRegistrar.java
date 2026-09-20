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
 * 启动期把命令清单**按权限分档**注册到 Telegram——即客户端里输入 {@code /} 时弹出的提示菜单。
 *
 * <p><b>为什么必须分档</b>：{@code setMyCommands} 不带 scope 就是「默认菜单」，**所有用户共享同一份**。
 * 早先的实现正是如此——把全部命令一次性注册，于是任何普通成员都能在客户端看到
 * {@code review_approve} / {@code merchant_settle} / {@code data_breach} 这些平台命令的名字。
 * 那与项目「权限不足即静默、不向无权者暴露命令存在」的纪律相悖。
 *
 * <p><b>分档规则</b>（见 {@link #planMenus}，由既有事实推导，不维护新的「命令→档位」表）：
 * <ul>
 *   <li><b>接缝类</b>（被某 {@code MenuVisibility} 认领的平台白名单命令）→ <b>不进任何档</b>。
 *       Telegram 只有 per-{@code (chat,user)} 的 scope、**没有「全局按人」的 scope**，
 *       而这些命令的授权是全局 userId 白名单、与群无关——它们由 {@code /menu} 卡片的可见性接缝呈现。</li>
 *   <li><b>公开类</b>（无权限点且非接缝）→ {@link BotCommandScopeDefault}，人人可见。</li>
 *   <li><b>管理类</b>（{@code requiredPermission != NONE} 且非接缝）→ 只进「该群该人有权限」的
 *       {@link BotCommandScopeChatMember}，按该条授权的 {@code Role} 逐条过滤——
 *       例如 {@code MODERATOR} 不该看到它跑不了的 {@code /words}。</li>
 * </ul>
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
     * 组装分档菜单（**纯函数**，便于单测）。
     *
     * @param registry    命令注册表（提供主命令、描述与所需权限）
     * @param seamCommands 被可见性接缝认领的命令名（即平台白名单类，不进客户端菜单）
     * @param grants      已授权项（{@code tgg.permission.admins} 解析所得）
     * @return 至少含一个默认档；逐成员档仅在「该授权确实能多看到至少一条管理命令」时才生成
     */
    public static List<ScopedMenu> planMenus(CommandRegistry registry,
                                             Set<String> seamCommands,
                                             List<RoleGrant> grants) {
        Map<String, String> publicSpec = new LinkedHashMap<>();
        Map<String, String> managementSpec = new LinkedHashMap<>();
        List<String> unlisted = new ArrayList<>();
        for (Map.Entry<String, String> entry : registry.mainCommands().entrySet()) {
            String name = entry.getKey();
            if (seamCommands.contains(name)) {
                // 接缝类：平台白名单，授权与群无关，Telegram 没有「全局按人」的 scope——由 /menu 负责
                continue;
            }
            if (registry.requiredPermission(name) != Permission.NONE) {
                managementSpec.put(name, entry.getValue());
                continue;
            }
            if (registry.publicCommand(name)) {
                publicSpec.put(name, entry.getValue());
                continue;
            }
            // fail-closed：无权限点、未被接缝认领、又没声明 publicCommand → **不进任何档**。
            // 反过来（默认公开）会让「门控在 handler 内、忘了登记接缝」的新命令被广播给所有人
            // ——正是「客户端菜单向无权者暴露命令」那个原始缺陷的原样回归。
            unlisted.add(name);
        }
        if (!unlisted.isEmpty()) {
            log.warn("以下命令不进任何客户端菜单档（无权限点、未被可见性接缝认领、也未声明 "
                            + "@BotCommand(publicCommand = true)）：{}"
                            + "——若本该对所有人可见请补 publicCommand；若是平台门控命令，"
                            + "请为其模块注册 MenuVisibility 接缝。",
                    String.join(", ", unlisted));
        }

        List<ScopedMenu> planned = new ArrayList<>();
        List<BotCommand> publicCommands = toMenuCommands(publicSpec);
        planned.add(new ScopedMenu(new BotCommandScopeDefault(), "default", publicCommands));

        for (RoleGrant grant : grants) {
            Map<String, String> forGrant = new LinkedHashMap<>(publicSpec);
            boolean extraManagement = false;
            for (Map.Entry<String, String> entry : managementSpec.entrySet()) {
                // 按**该条授权的角色**过滤：MODERATOR 只有 BAN_USER，不该看到 /words 这类管理命令
                if (grant.role().has(registry.requiredPermission(entry.getKey()))) {
                    forGrant.put(entry.getKey(), entry.getValue());
                    extraManagement = true;
                }
            }
            if (!extraManagement) {
                // 该授权不额外看到任何管理命令（如 MODERATOR/MEMBER）——不浪费一次 API 调用，
                // 它会自然落到默认档
                continue;
            }
            planned.add(new ScopedMenu(
                    new BotCommandScopeChatMember(String.valueOf(grant.chatId()), grant.userId()),
                    "member:" + grant.chatId() + ":" + grant.userId(),
                    toMenuCommands(forGrant)));
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

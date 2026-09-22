package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.exception.TggDispatchException;
import com.tg.heyisheng.bot.core.permission.Permission;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 命令注册表：启动时扫描 {@link BotCommand} 标注的 bean，建立「命令名/别名 → 处理器 + 所需权限」映射。
 *
 * <p>命令名与别名统一归一化为小写、去前导斜杠、去 {@code @BotName} 后缀，
 * 因此 {@code /Echo@MyBot}、{@code /echo}、{@code echo} 命中同一项。
 *
 * <p>冲突（两个不同处理器注册同名命令）在构造期即失败，不留到运行期。
 */
public class CommandRegistry {

    /** 一条命令的注册项。 */
    private record Entry(CommandHandler handler, Permission permission, boolean worksWhenDisabled,
                         Confirm confirm, MenuCategory category, boolean publicCommand,
                         boolean clientMenu, boolean groupOnly) {
    }

    private final Map<String, Entry> entries;

    /** 主命令 → 描述（**不含别名**，按命令名排序）。Telegram 命令菜单的唯一事实来源。 */
    private final Map<String, String> mainCommands;

    public CommandRegistry(List<?> commandBeans) {
        Map<String, Entry> map = new HashMap<>();
        Map<String, String> menu = new HashMap<>();
        for (Object bean : commandBeans) {
            // 必须按「最终目标类」读注解：命令处理器可能被 Spring AOP 代理
            // （模块十的审计切面即切入本方法），JDK 动态代理下代理类不继承类级注解，
            // 直接用 bean.getClass() 会读不到 @BotCommand → 命令静默消失。
            // AopProxyUtils.ultimateTargetClass 对非代理 bean 返回其自身类，行为不变。
            BotCommand annotation = AnnotationUtils.findAnnotation(
                    AopProxyUtils.ultimateTargetClass(bean), BotCommand.class);
            if (annotation == null) {
                continue;
            }
            if (!(bean instanceof CommandHandler handler)) {
                throw new TggDispatchException(
                        "@BotCommand 标注的类必须实现 CommandHandler：" + bean.getClass().getName());
            }
            String owner = bean.getClass().getName();
            Entry entry = new Entry(handler, annotation.requiredPermission(),
                    annotation.worksWhenDisabled(), annotation.confirm(), annotation.category(),
                    annotation.publicCommand(), annotation.clientMenu(), annotation.groupOnly());
            register(map, annotation.value(), entry, owner);
            // 只把**主命令**收进菜单视图：别名（如 /ping）在客户端菜单里是噪声。
            // description() 自切片 1 起一直无人消费——命令菜单正是它的第一个消费者。
            menu.put(normalize(annotation.value()),
                    annotation.description() == null ? "" : annotation.description());
            for (String alias : annotation.aliases()) {
                register(map, alias, entry, owner);
            }
        }
        this.entries = Map.copyOf(map);
        // TreeMap：输出按名字稳定排序，菜单与日志顺序才不会随机抖动
        this.mainCommands = Collections.unmodifiableMap(new TreeMap<>(menu));
    }

    private static void register(Map<String, Entry> map, String name, Entry entry, String owner) {
        String key = normalize(name);
        if (key.isEmpty()) {
            throw new TggDispatchException("@BotCommand 命令名为空：" + owner);
        }
        Entry existing = map.putIfAbsent(key, entry);
        if (existing != null && existing.handler() != entry.handler()) {
            throw new TggDispatchException("命令名冲突 '" + key + "'（来自 " + owner + "）");
        }
    }

    /** 归一化：去前导斜杠与 @BotName 后缀，转小写。 */
    static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        int at = s.indexOf('@');
        if (at >= 0) {
            s = s.substring(0, at);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    public Optional<CommandHandler> find(String command) {
        return Optional.ofNullable(entries.get(normalize(command))).map(Entry::handler);
    }

    /**
     * 查询某命令所需的权限。
     *
     * @return 该命令声明的权限；命令不存在时返回 {@link Permission#NONE}
     */
    public Permission requiredPermission(String command) {
        Entry entry = entries.get(normalize(command));
        return entry == null ? Permission.NONE : entry.permission();
    }

    /**
     * 查询某命令是否可在「该群功能已关闭」时执行。
     *
     * @return 命令不存在时返回 {@code false}（未知命令本就不会被执行）
     */
    public boolean worksWhenDisabled(String command) {
        Entry entry = entries.get(normalize(command));
        return entry != null && entry.worksWhenDisabled();
    }

    /**
     * 查询某命令是否**只能在群里用**（见 {@link BotCommand#groupOnly()}）。
     *
     * @return 命令不存在时返回 {@code false}（未知命令本就不会被执行）
     */
    public boolean groupOnly(String command) {
        Entry entry = entries.get(normalize(command));
        return entry != null && entry.groupOnly();
    }

    /**
     * 查询某命令的确认模式（见 {@link Confirm}）。
     *
     * @return 命令不存在时返回 {@link Confirm#NEVER}——未知命令本就不会被执行，无需打扰用户
     */
    public Confirm confirmationOf(String command) {
        Entry entry = entries.get(normalize(command));
        return entry == null ? Confirm.NEVER : entry.confirm();
    }

    /**
     * 查询某命令在 {@code /menu} 面板里的业务域分类。
     *
     * @return 该命令声明的分类；命令不存在时返回 {@link MenuCategory#OTHER}
     *         （未知命令本就不会出现在面板上，兜底值不影响任何行为）
     */
    public MenuCategory categoryOf(String command) {
        Entry entry = entries.get(normalize(command));
        return entry == null ? MenuCategory.OTHER : entry.category();
    }

    /**
     * 查询某命令是否**对全体成员公开**（见 {@link BotCommand#publicCommand()}）。
     *
     * @return 命令不存在时返回 {@code false}——未知命令本就不该被公开广播（fail-closed）
     */
    public boolean publicCommand(String command) {
        Entry entry = entries.get(normalize(command));
        return entry != null && entry.publicCommand();
    }

    /**
     * 查询某命令是否**进客户端 {@code /} 提示菜单**（见 {@link BotCommand#clientMenu()}）。
     *
     * @return 命令不存在时返回 {@code false}——未知命令本就不该出现在客户端菜单（fail-closed）
     */
    public boolean clientMenu(String command) {
        Entry entry = entries.get(normalize(command));
        return entry != null && entry.clientMenu();
    }

    public Set<String> registeredCommands() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    /**
     * 主命令 → 描述（不含别名，按名字排序）。
     *
     * <p>供启动期注册 Telegram 命令菜单使用——即 {@code @BotCommand.description()} 的消费者。
     * 注册表只陈述事实：**描述是否为空、是否合 Telegram 的格式要求，由组装层决定**
     * （见 {@link CommandMenuRegistrar}）。
     */
    public Map<String, String> mainCommands() {
        return mainCommands;
    }

    public int size() {
        return entries.size();
    }
}

package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.exception.TggDispatchException;
import com.tg.heyisheng.bot.core.permission.Permission;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    private record Entry(CommandHandler handler, Permission permission) {
    }

    private final Map<String, Entry> entries;

    public CommandRegistry(List<?> commandBeans) {
        Map<String, Entry> map = new HashMap<>();
        for (Object bean : commandBeans) {
            BotCommand annotation = AnnotationUtils.findAnnotation(bean.getClass(), BotCommand.class);
            if (annotation == null) {
                continue;
            }
            if (!(bean instanceof CommandHandler handler)) {
                throw new TggDispatchException(
                        "@BotCommand 标注的类必须实现 CommandHandler：" + bean.getClass().getName());
            }
            String owner = bean.getClass().getName();
            Entry entry = new Entry(handler, annotation.requiredPermission());
            register(map, annotation.value(), entry, owner);
            for (String alias : annotation.aliases()) {
                register(map, alias, entry, owner);
            }
        }
        this.entries = Map.copyOf(map);
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

    public Set<String> registeredCommands() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    public int size() {
        return entries.size();
    }
}

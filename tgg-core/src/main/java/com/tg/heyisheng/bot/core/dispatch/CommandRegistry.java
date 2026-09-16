package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.exception.TggDispatchException;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 命令注册表：启动时扫描 {@link BotCommand} 标注的 bean，建立「命令名/别名 → 处理器」映射。
 *
 * <p>命令名与别名统一归一化为小写、去前导斜杠、去 {@code @BotName} 后缀，
 * 因此 {@code /Echo@MyBot}、{@code /echo}、{@code echo} 命中同一处理器。
 *
 * <p>冲突（两个不同处理器注册同名命令）在构造期即失败，不留到运行期。
 */
public class CommandRegistry {

    private final Map<String, CommandHandler> handlers;

    public CommandRegistry(List<?> commandBeans) {
        Map<String, CommandHandler> map = new HashMap<>();
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
            register(map, annotation.value(), handler, owner);
            for (String alias : annotation.aliases()) {
                register(map, alias, handler, owner);
            }
        }
        this.handlers = Map.copyOf(map);
    }

    private static void register(Map<String, CommandHandler> map, String name,
                                 CommandHandler handler, String owner) {
        String key = normalize(name);
        if (key.isEmpty()) {
            throw new TggDispatchException("@BotCommand 命令名为空：" + owner);
        }
        CommandHandler existing = map.putIfAbsent(key, handler);
        if (existing != null && existing != handler) {
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
        return Optional.ofNullable(handlers.get(normalize(command)));
    }

    public Set<String> registeredCommands() {
        return Collections.unmodifiableSet(handlers.keySet());
    }

    public int size() {
        return handlers.size();
    }
}

package com.tg.heyisheng.bot.core.permission;

import com.tg.heyisheng.bot.common.exception.TggConfigException;

import java.util.Locale;

/**
 * 把配置字符串解析为角色授权。
 *
 * <p>格式（逗号分隔，空白忽略）：
 * <pre>
 *   &lt;chatId&gt;:&lt;userId&gt;[:role]
 *   例如：-1001234567890:42:ADMIN, -1009876543210:7
 * </pre>
 * 省略 {@code role} 时默认 {@link Role#ADMIN}。
 *
 * <p><b>为什么要有它</b>：切片 3b 交付时 {@code RoleSource} 在测试之外没有任何调用方，
 * 也没有任何授予入口——角色维度在生产中不可达，权限门控因此形同虚设。
 * 本类提供最小可用的生产授权通道：**改配置即可授权**，无需等数据库落地。
 */
public final class RoleGrantParser {

    private RoleGrantParser() {
    }

    /**
     * 逐条解析并写入角色源。
     *
     * @param spec 配置字符串，可为 null 或空（表示无授权）
     * @throws TggConfigException 格式非法——配置错误应在启动期暴露，而非静默忽略
     */
    public static void apply(InMemoryRoleSource source, String spec) {
        if (spec == null || spec.isBlank()) {
            return;
        }
        for (String raw : spec.split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            source.assign(parseChatId(item), parseUserId(item), parseRole(item));
        }
    }

    private static long parseChatId(String item) {
        return parseLong(part(item, 0, item), "chatId", item);
    }

    private static long parseUserId(String item) {
        return parseLong(part(item, 1, item), "userId", item);
    }

    private static Role parseRole(String item) {
        // 必须用 split(":", -1) —— 默认的 split(":") 会丢弃尾部空串，
        // 使 "-100:42:"（漏填角色名）与 "-100:42"（有意省略角色段）无法区分，
        // 导致配置笔误被静默授予 ADMIN。该行为已用探针实测确认。
        String[] parts = item.split(":", -1);
        if (parts.length < 3) {
            // 真正地省略了整个角色段
            return Role.ADMIN;
        }
        if (parts[2].isBlank()) {
            throw new TggConfigException("授权项写了角色分隔符但未填角色名：'" + item
                    + "'（应写成 <chatId>:<userId>:<role>，或整体省略角色段）");
        }
        try {
            return Role.valueOf(parts[2].trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new TggConfigException("无法识别的角色 '" + parts[2].trim() + "'（授权项：" + item + "）");
        }
    }

    private static String part(String item, int index, String whole) {
        String[] parts = item.split(":", -1);
        if (parts.length <= index || parts[index].isBlank()) {
            throw new TggConfigException(
                    "授权项格式非法 '" + whole + "'，应为 <chatId>:<userId>[:role]");
        }
        return parts[index].trim();
    }

    private static long parseLong(String value, String field, String whole) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new TggConfigException(field + " 不是合法数字：'" + value + "'（授权项：" + whole + "）");
        }
    }
}

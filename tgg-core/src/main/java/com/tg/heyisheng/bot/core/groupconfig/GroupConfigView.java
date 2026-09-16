package com.tg.heyisheng.bot.core.groupconfig;

/**
 * 群组配置的只读视图。
 *
 * <p>刻意不直接把 JPA 实体传给中间件与 handler：
 * 实体是游离态（脱离事务后懒加载会抛错）、且带可变 setter，
 * 传出去容易让业务代码误改后以为已持久化。
 */
public record GroupConfigView(Long chatId, String title, boolean enabled) {

    /**
     * 未登记的群组的默认配置。
     *
     * <p>默认 {@code enabled = true}：功能默认开启，避免"机器人进群后什么都不做"。
     * 若将来要求默认关闭，改这里一处即可。
     */
    public static GroupConfigView defaultFor(Long chatId) {
        return new GroupConfigView(chatId, null, true);
    }
}

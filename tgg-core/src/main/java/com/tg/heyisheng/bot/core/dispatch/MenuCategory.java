package com.tg.heyisheng.bot.core.dispatch;

import java.util.Locale;
import java.util.Optional;

/**
 * {@code /menu} 面板的**业务域分类**（模块九 §7.4 可见性接缝的配套）。
 *
 * <p><b>为什么是枚举而不是映射表</b>：命令属于哪一类，由命令自己在
 * {@link BotCommand#category()} 里声明——与 {@code requiredPermission}、{@code confirm} 同款。
 * 若在菜单侧维护一份「命令 → 分类」的表，新增命令时必然有人忘了登记，表会**悄悄过期**
 * （本项目在 {@code MenuCallbackHandler} 的用法文案上已有同类教训）。
 *
 * <p><b>{@link #OTHER} 是兜底而非垃圾桶</b>：未分类但**可见**的命令会落进这里并照常显示
 * （fail-visible——宁可多一个「其他」，也不让命令从面板里静默消失）。测试锁住「能进面板的命令
 * 都得有明确分类」，防止长期依赖兜底。
 *
 * <p><b>{@code key}</b>：回调 {@code data} 里用的稳定标识（小写枚举名），据此回查分类，
 * 不把中文标题塞进 {@code data}（长度与转义都更可控）。
 */
public enum MenuCategory {

    /** 内容审核：词库、教学规则等。 */
    MODERATION("内容审核"),

    /** 群设置：群级开关与群属性声明。 */
    GROUP("群设置"),

    /** 复核合规：平台层复核队列、合规证据、联邦申诉裁决。 */
    REVIEW("复核合规"),

    /** 收录商家：收录库与商家资质流程。 */
    LISTING("收录商家"),

    /** 兜底：尚未声明分类的可见命令。 */
    OTHER("其他");

    private final String title;

    MenuCategory(String title) {
        this.title = title;
    }

    /** 面板上显示的中文分类名。 */
    public String title() {
        return title;
    }

    /** 回调 {@code data} 里使用的稳定 key（小写枚举名）。 */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 由 {@link #key()} 反查分类。
     *
     * <p>不认识（含 {@code null}、大小写不符、空白）一律返回空——调用方据此判「非法 key」，
     * 不猜、不静默降级。
     */
    public static Optional<MenuCategory> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (MenuCategory category : values()) {
            if (category.key().equals(normalized)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}

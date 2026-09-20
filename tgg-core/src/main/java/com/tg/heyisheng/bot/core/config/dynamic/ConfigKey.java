package com.tg.heyisheng.bot.core.config.dynamic;

/**
 * 一个配置键的**元数据描述**（配置中心的单一事实来源）。
 *
 * <p>它同时驱动三件事：① 只读总览要列出哪些键、怎么分类；② 写入时允许改哪些键；
 * ③ 写入时如何校验。三者共用一份声明，就不会出现「前端能点、后端不认」或「文档写了、代码没有」。
 *
 * @param key                     Spring 属性名（如 {@code tgg.admin.overdue-remind-hours}）
 * @param category                语义分类（决定可否写）
 * @param type                    值类型（决定写入校验）
 * @param editable                是否允许经 Web 改写（密钥/引导态恒 false）
 * @param restartRequired         改后是否需重启才生效（装配开关恒 true；未改造为调用期读取的运行参数亦 true）
 * @param secret                  是否敏感（总览里打码，永不回显明文）
 * @param defaultValue            默认值（{@code application.yml} 的兜底；无则 null）
 * @param min                     数值下界（含；仅 INT / LONG / HOURS 有意义，否则 null）
 * @param max                     数值上界（含；同上）
 * @param requiresKeyWhenEnabled  当本键被置为 {@code true} 时，另一个必须非空的键（仅装配开关有意义，否则 null）
 * @param description             人读说明——尤其要写清「不设会怎样」
 */
public record ConfigKey(
        String key,
        ConfigCategory category,
        ConfigValueType type,
        boolean editable,
        boolean restartRequired,
        boolean secret,
        String defaultValue,
        Integer min,
        Integer max,
        String requiresKeyWhenEnabled,
        String description) {

    /** 是否允许经 Web 改写——分类与 editable 的合取（密钥/引导态即便误标 editable 也不放行）。 */
    public boolean writable() {
        return editable
                && category != ConfigCategory.SECRET
                && category != ConfigCategory.BOOTSTRAP;
    }
}

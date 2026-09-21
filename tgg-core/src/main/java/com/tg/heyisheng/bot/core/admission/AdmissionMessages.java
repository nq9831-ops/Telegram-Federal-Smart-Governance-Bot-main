package com.tg.heyisheng.bot.core.admission;

/**
 * 准入与验证（模块四）的**用户可见文案**——{@code *Messages} 家族在 admission 包的落点。
 *
 * <p><b>措辞冻结</b>：所有字符串**逐字节**取自搬迁前的原实现（含全角标点、空格与前导空格），
 * 搬迁不改一个字节。
 *
 * <p>组织规则见 {@code VOICE.md} 第八节。
 */
public final class AdmissionMessages {

    private AdmissionMessages() {
    }

    // ────────────── 点按验证按钮的回执 ──────────────

    /** 验证通过。 */
    public static final String PASSED = "验证通过，欢迎加入。";

    /** 点了别人的按钮。 */
    public static final String NOT_YOURS = "这不是你的验证按钮。点你自己那条验证消息里的按钮就行。";

    /** 验证已过期 / 已完成 / data 非法。 */
    public static final String EXPIRED = "这个验证已经失效了（过期或已完成）。请联系群管理员。";

    // ────────────── 入群验证消息 ──────────────

    /** 验证提示（含一个 {@code %s}：时限）。 */
    public static final String PROMPT_TEXT = "欢迎加入。点一下下方按钮就完成验证；"
            + "%s 内没点的话，会被移出本群。";

    /** 验证按钮文案。 */
    public static final String BUTTON_TEXT = "点击验证";

    // ────────────── 时长渲染（面向普通成员，不是运维）──────────────

    /** 时长单位：天。 */
    public static final String UNIT_DAYS = " 天";

    /** 时长单位：小时。 */
    public static final String UNIT_HOURS = " 小时";

    /** 时长单位：分钟。 */
    public static final String UNIT_MINUTES = " 分钟";

    /** 时长单位：秒（非整档时的兜底）。 */
    public static final String UNIT_SECONDS = " 秒";
}

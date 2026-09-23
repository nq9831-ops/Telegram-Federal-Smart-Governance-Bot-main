package com.tg.heyisheng.bot.core.notify;

/**
 * 通知域（模块十 §11.1）的**用户可见文案**——{@code *Messages} 家族在 notify 包的落点。
 *
 * <p><b>措辞冻结</b>：所有字符串**逐字节**取自搬迁前的原实现（含全角标点、换行与空格），
 * 搬迁不改一个字节——证据是现有逐字断言测试保持绿。
 *
 * <p>固定句子用常量；需要插值的（如时段区间、合并条数）用静态方法——
 * 两者都把文案收在这里一处。
 *
 * <p>规范见仓库根的 {@code VOICE.md}（进版本库，与 {@code README.md} 同族）。
 */
public final class NotifyMessages {

    private NotifyMessages() {
    }

    // ────────────── /quiet_hours ──────────────

    /** {@code /quiet_hours} 用法。 */
    public static final String QUIET_HOURS_USAGE = "用法：\n"
            + "/quiet_hours 22:00-08:00 —— 设置免打扰时段（支持跨午夜）\n"
            + "/quiet_hours off —— 取消免打扰\n"
            + "/quiet_hours —— 查看当前设置\n"
            + "注：封禁、解封这些紧急通知不受免打扰影响，照常送到。";

    /** 查看当前设置：未设置时的回执。 */
    public static final String QUIET_HOURS_NONE_SET = "你还没设免打扰，消息都会照常送到。";

    /** 取消成功的回执。 */
    public static final String QUIET_HOURS_CLEARED = "免打扰取消了。";

    /** 本就未设置、无可取消时的回执。 */
    public static final String QUIET_HOURS_NOTHING_TO_CLEAR = "你还没设过免打扰，不用取消。";

    /** 时段格式不对（后接用法）。 */
    public static final String QUIET_HOURS_BAD_FORMAT = "这个时段我没看懂，换个写法——\n";

    /** 查看当前设置：已设置时的回执。 */
    public static String quietHoursCurrent(String range) {
        return "你的免打扰时段：" + range + "。紧急通知不受影响，照常送到。";
    }

    /** 设置成功的回执。 */
    public static String quietHoursSet(String range) {
        return "免打扰设好了：" + range
                + "。\n这段时间里的普通/重要通知会等到结束后再发；紧急通知（封禁、解封这些）不受影响。";
    }

    // ────────────── 通知分发 ──────────────

    /** 被频率门抑制后并入摘要的提示。 */
    public static String mergedNotice(int count) {
        return "\n（另外 " + count + " 条同类的并进来了）";
    }
}

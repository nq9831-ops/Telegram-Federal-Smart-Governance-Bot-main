package com.tg.heyisheng.bot.core.breach;

/**
 * 数据泄露登记与通报（模块十 §11.2）的**用户可见文案**——{@code *Messages} 家族在 breach 包的落点。
 *
 * <p><b>措辞冻结</b>：所有字符串**逐字节**取自搬迁前的原实现（含全角标点、换行与前导空格），
 * 搬迁不改一个字节。
 *
 * <p>组织规则见 {@code VOICE.md} 第八节：整句用常量；含插值的整行/整段用静态方法
 * （如 {@link #pendingLine} 承载「发现 / 截止 / 影响人数 / 范围」四处插值），不硬拆碎片常量。
 */
public final class BreachMessages {

    private BreachMessages() {
    }

    // ────────────── /data_breach ──────────────

    /** {@code /data_breach} 用法。 */
    public static final String USAGE = "用法：\n"
            + "/data_breach 影响范围 影响人数 —— 登记一起泄露事件（72 小时计时开始），例：/data_breach 用户名与手机号 1200\n"
            + "/data_breach report 编号 —— 记录已完成通报，例：/data_breach report 1\n"
            + "/data_breach —— 列出还没通报的事件\n"
            + "注：通报是运营者的法定义务，本命令只做计时与留痕。";

    /** 登记失败前缀（后接原因）。 */
    public static final String REGISTER_FAILED_PREFIX = "登记未成功：";

    /** 没有未通报事件。 */
    public static final String PENDING_EMPTY = "现在没有未通报的数据泄露事件。要登记新事件，用 /data_breach。";

    /** 未通报列表标题。 */
    public static final String PENDING_HEAD = "还没通报的数据泄露事件：\n";

    /** 未通报列表脚注。 */
    public static final String PENDING_FOOTER = "通报做完后记一笔：/data_breach report 编号（例：/data_breach report 1）";

    /** 登记成功的回执。 */
    public static String registered(Object id, Object deadline) {
        return "已登记泄露事件 #" + id + "，要在 " + deadline
                + " 前完成通报（72 小时内）。通报做完后用 /data_breach report " + id + " 记一笔。";
    }

    /** 已记录通报的回执。 */
    public static String reportRecorded(Object id) {
        return "记下了：泄露事件 #" + id + " 的通报已完成。";
    }

    /** 事件不存在、或已记录过通报。 */
    public static String reportMissingOrDone(Object id) {
        return "事件 #" + id + " 不存在，或者已经记录过通报了（首次通报时间不覆盖——那是合规证据）。"
                + "用 /data_breach 看当前还没通报的清单。";
    }

    /** 未通报列表的一行（含编号 / 发现时间 / 截止时间 / 影响人数 / 范围五处插值）。 */
    public static String pendingLine(Object id, Object detectedAt, Object deadline,
                                     Object affectedCount, Object scope) {
        return "#" + id
                + " · 发现 " + detectedAt
                + " · 截止 " + deadline
                + " · 影响约 " + affectedCount + " 人\n"
                + "   范围：" + scope + "\n";
    }

    /** 泄露通报的催办（发给平台白名单成员，紧急级）。 */
    public static String reminder(Object id, Object scope, Object affectedCount, Object deadline) {
        return "⚠️ 数据泄露事件 #" + id + " 还没通报："
                + scope
                + "（预计影响 " + affectedCount + " 人，截止 "
                + deadline + "）。通报做完之后，用 /data_breach report "
                + id + " 记一笔。";
    }
}

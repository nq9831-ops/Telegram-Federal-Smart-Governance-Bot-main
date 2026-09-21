package com.tg.heyisheng.bot.federation;

/**
 * 联邦治理（模块八：申诉提交 / 待审列表 / 通过 / 驳回）的**用户可见文案**
 * ——{@code *Messages} 家族在 federation 包的落点。
 *
 * <p><b>措辞冻结</b>：所有字符串**逐字节**取自搬迁前的原实现，搬迁不改一个字节。
 *
 * <p>组织规则见仓库根 {@code VOICE.md} 第八节。
 */
public final class FederationMessages {

    private FederationMessages() {
    }

    // ────────────── /appeal ──────────────

    /** {@code /appeal} 用法。 */
    public static final String APPEAL_USAGE = "用法：/appeal 申诉内容\n例：/appeal 我的账号被误封了";

    /** 申诉提交成功。 */
    public static String appealSubmitted(Object id) {
        return "申诉已提交（编号 #" + id + "），将由联邦管理员审核。";
    }

    // ────────────── /approve · /reject ──────────────

    /** {@code /approve} 用法。 */
    public static final String APPROVE_USAGE = "用法：/approve 申诉编号\n例：/approve 1";

    /** {@code /reject} 用法。 */
    public static final String REJECT_USAGE = "用法：/reject 申诉编号\n例：/reject 1";

    /** 通过回执。 */
    public static String approved(Object id) {
        return "申诉 #" + id + " 已通过。";
    }

    /** 驳回回执。 */
    public static String rejected(Object id) {
        return "申诉 #" + id + " 已驳回。";
    }

    /** 编号不存在。 */
    public static String notFound(Object id) {
        return "未找到申诉 #" + id + "。";
    }

    // ────────────── /pending ──────────────

    /** 没有待审申诉。 */
    public static final String PENDING_EMPTY = "当前没有待审申诉。";

    /** 待审列表标题前缀（后接条数）。 */
    public static final String PENDING_HEAD_PREFIX = "待审申诉（共 ";

    /** 待审列表标题后缀。 */
    public static final String PENDING_HEAD_SUFFIX = " 条）：\n";

    /** 待审列表的一项。 */
    public static String pendingItem(Object id, Object appealType) {
        return "#" + id + " [" + appealType + "]";
    }
}

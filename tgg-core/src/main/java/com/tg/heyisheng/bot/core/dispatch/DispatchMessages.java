package com.tg.heyisheng.bot.core.dispatch;

/**
 * 命令分发 / 面板分类的**用户可见文案**——{@code *Messages} 家族在 dispatch 包的落点。
 *
 * <p>覆盖三块交互面文案：连通性测试回复、危险操作确认卡、{@code /menu} 的业务域分类名。
 * 命令自身的用法与回执仍留在各自 handler（本步只搬交互面，见计划的收口说明）。
 *
 * <p><b>措辞冻结</b>：字符串**逐字节**取自搬迁前的原实现，搬迁不改一个字节——
 * 证据是现有逐字断言测试保持绿。
 *
 * <p>规范见仓库根的 {@code VOICE.md}（进版本库，与 {@code README.md} 同族）。
 */
public final class DispatchMessages {

    private DispatchMessages() {
    }

    // ────────────── 连通性测试 ──────────────

    /** {@code /echo} 的固定回复。 */
    public static final String ECHO_REPLY = "在的，连通没问题。";

    /**
     * **未注册 / 拼写错误**命令的固定回复。
     *
     * <p>此前未注册命令是<b>静默丢弃</b>（不回复、不报错）：用户敲错命令后得不到任何反馈，
     * 只会干等——看起来像机器人坏了（见 {@code CommandDispatcher#dispatch}）。
     * 这里给一条固定提示，并按 VOICE 的「拒绝要给出去路」附上可照抄的 {@code /menu}。
     *
     * <p><b>刻意不回显用户输入的命令名</b>：回显会把用户正文重新写回群里与日志，
     * 与「消息原文零存储」冲突；固定文案同时也避免把它变成模板注入点。
     */
    public static final String UNKNOWN_COMMAND_REPLY = "没找到这条命令。发 /menu 看一眼都有哪些功能吧。";

    /**
     * 「需在群会话内执行」的统一提示。
     *
     * <p>刻意放在这里（跨域共享）而**不**各域各写一份：wordfilter 的 {@code /teach} 与 moderation 的
     * {@code /group_tag} 都要用它，两处各写一份就是「改一处必然漏另一处」——
     * 守门测试的「不得重复散落」判据也会拦下。
     */
    public static final String GROUP_ONLY = "这条命令得在群里发才管用。";

    /**
     * 「申诉已提交（编号 #」前缀——联邦申诉（模块八）与收录申诉（模块五）共用。
     *
     * <p>同样刻意跨域共享：两处各写一份的话，改一处必然漏另一处，
     * 守门测试的「不得重复散落」判据也会拦下。
     */
    public static final String APPEAL_SUBMITTED_PREFIX = "申诉已提交（编号 #";

    /**
     * 列表截断提示——教学规则列表（模块九）与收录库列表（模块五）逐字相同，故共享一份。
     *
     * <p>Bot API 单条消息有 4096 字符上限，超长会**整条发送失败**（回复直接丢掉、调用方还以为成功），
     * 故两处列表都必须截断说明。
     */
    public static String truncatedNotice(int total, int shown) {
        return "\n…（已截断：共 " + total + " 条，本条只显示前 " + shown + " 条）";
    }

    /** {@code /enable} 的回执。 */
    public static final String ENABLE_REPLY = "本群的自动化能力开好了。";

    /** {@code /disable} 的回执。 */
    public static final String DISABLE_REPLY = "本群的自动化能力关掉了。";

    // ────────────── 确认卡 ──────────────

    /** 确认按钮。 */
    public static final String CONFIRM_BUTTON = "✅ 确认执行";

    /** 取消按钮。 */
    public static final String CANCEL_BUTTON = "❌ 取消";

    /**
     * 确认卡正文：复述将要执行的命令。
     *
     * <p>刻意不写「该操作不可撤销」——被标需要确认的命令多数并非严格不可逆，
     * 一律断言会让用户学会无视这句话（见 {@code CommandDispatcher#confirmationCard} 的取舍）。
     */
    public static String confirmPrompt(String preview) {
        return "马上要执行：" + preview + "\n先核对一下命令和参数；确认后我立刻执行。";
    }

    // ────────────── /menu 业务域分类名 ──────────────

    /** 分类名：自助功能。 */
    public static final String CATEGORY_SELF_SERVICE = "自助功能";

    /** 分类名：内容审核。 */
    public static final String CATEGORY_MODERATION = "内容审核";

    /** 分类名：群设置。 */
    public static final String CATEGORY_GROUP = "群设置";

    /** 分类名：复核合规。 */
    public static final String CATEGORY_REVIEW = "复核合规";

    /** 分类名：群组收录。 */
    public static final String CATEGORY_LISTING = "群组收录";

    /** 分类名：商家收录。 */
    public static final String CATEGORY_MERCHANT = "商家收录";

    /** 分类名：兜底。 */
    public static final String CATEGORY_OTHER = "其他";
}

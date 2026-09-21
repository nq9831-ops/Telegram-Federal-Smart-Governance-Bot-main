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
 * <p>规范见仓库根的 {@code VOICE.md}（进版本库，与 {@code ARCHITECTURE.md} 同族）。
 */
public final class DispatchMessages {

    private DispatchMessages() {
    }

    // ────────────── 连通性测试 ──────────────

    /** {@code /echo} 的固定回复。 */
    public static final String ECHO_REPLY = "pong";

    /** {@code /enable} 的回执。 */
    public static final String ENABLE_REPLY = "已开启本群的自动化能力。";

    /** {@code /disable} 的回执。 */
    public static final String DISABLE_REPLY = "已关闭本群的自动化能力。";

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
        return "即将执行：" + preview + "\n请核对命令与参数；确认后将立即执行。";
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

    /** 分类名：收录商家。 */
    public static final String CATEGORY_LISTING = "收录商家";

    /** 分类名：兜底。 */
    public static final String CATEGORY_OTHER = "其他";
}

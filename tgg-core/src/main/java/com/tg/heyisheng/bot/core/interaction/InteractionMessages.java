package com.tg.heyisheng.bot.core.interaction;

/**
 * 交互层（按钮回调 / 确认卡 / 面板 / 身份）的**用户可见文案**——{@code *Messages} 家族在 interaction 包的落点。
 *
 * <p><b>为什么单独建类</b>：这些文案原本在多个类里**各写一份**——{@code UNKNOWN = "未知操作。"}
 * 在 {@code MenuCallbackHandler} 与 {@code CallbackCommandBridge} 里一字不差地重复，
 * 改一处必然漏另一处（本项目「两处各写一份条件就会漂移」的同类教训）。
 * 集中之后，第三步「语气铺开」只需改这里一处。
 *
 * <p><b>组织规则</b>：完整句子用常量；含插值的句子用静态方法；纯格式胶水（如 {@code /}、{@code  — }）
 * 留在各自的视图/格式化处，不在此处伪造碎片常量。
 *
 * <p><b>措辞冻结</b>：所有字符串**逐字节**取自搬迁前的原实现（含全角标点、换行与 emoji），
 * 搬迁不改一个字节——证据是现有逐字断言测试保持绿。
 *
 * <p>规范见 {@code docs/VOICE.md}。
 */
public final class InteractionMessages {

    private InteractionMessages() {
    }

    // ────────────── 按钮回调 ──────────────

    /** 回调 {@code data} 无法解析时的应答（按钮下方气泡，全群可见）。 */
    public static final String UNKNOWN = "未知操作。";

    /** 按钮触发被限流时的应答。 */
    public static final String TOO_FAST = "操作过于频繁，请稍后再试。";

    /** 按钮触发的命令执行失败时的应答（异常已在桥内兜住，不冒到 webhook）。 */
    public static final String FAILED = "执行失败，请稍后再试。";

    // ────────────── 确认卡 ──────────────

    /** 确认令牌失效（过期 / 已被使用 / 不是你的卡）——三者刻意共用一句，不向试探者区分。 */
    public static final String CONFIRM_EXPIRED = "该确认已失效（超时或已被使用），请重新发起。";

    /** 取消确认的应答（转瞬气泡）。 */
    public static final String CANCELLED = "已取消。";

    /** 取消确认后卡片的终态文案。 */
    public static final String CANCELLED_CARD = "❌ 已取消。";

    /** 确认后卡片置为终态、命令开始执行时的文案。 */
    public static String confirmRunning(String command) {
        return "✅ 已确认，正在执行 /" + command + "…";
    }

    // ────────────── /menu 面板 ──────────────

    /** 面板「← 返回」按钮文案。 */
    public static final String BACK_LABEL = "← 返回";

    /** 面板按权限过滤后一无所剩时的说明（刻意回复而非静默，见 MenuCommandHandler 的取舍）。 */
    public static final String MENU_NO_PERMISSION = "你在此群没有可用的功能。";

    /** 面板主页说明。 */
    public static final String MENU_HOME_HINT = "可用功能（只列出你在此群能用的）：\n选一个分类查看，点按钮直接执行。";

    // ────────────── 身份（/whoami 与面板身份行）──────────────

    /** 私聊里的身份回执。 */
    public static String identityPrivate(long userId) {
        return "你的 Telegram 用户 ID：\n" + userId
                + "\n\n配置复核人、群管理员等白名单时需要它。";
    }

    /** 群内（开关打开时）的身份回执：一并给出本群 ID，便于拼成授权配置项。 */
    public static String identityGroup(long chatId, long userId) {
        return "你的 Telegram 用户 ID：\n" + userId
                + "\n本群 ID：\n" + chatId
                + "\n\n配置本群授权时用冒号把两个数字连起来（先群 ID、后用户 ID）。";
    }

    /** 群内默认（开关关闭）的身份回执：不暴露明文 ID，只引导去私聊。 */
    public static final String IDENTITY_GROUP_HIDDEN =
            "身份信息不在群内展示，以免对全群可见。\n请私聊我发送 /whoami 查看。";

    /** 面板顶部的身份行。 */
    public static String identityLine(long userId) {
        return "你的 Telegram 用户 ID：" + userId;
    }

    /** /whoami 取不到身份时的兜底（命令链上不该发生，兜底不猜）。 */
    public static final String WHOAMI_UNIDENTIFIED = "无法识别你的身份，请稍后再试。";
}

package com.tg.heyisheng.bot.core.interaction;

import java.util.List;

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
 * <p>规范见仓库根的 {@code VOICE.md}（进版本库，与 {@code ARCHITECTURE.md} 同族）。
 */
public final class InteractionMessages {

    private InteractionMessages() {
    }

    // ────────────── 按钮回调 ──────────────

    /** 回调 {@code data} 无法解析时的应答（按钮下方气泡，全群可见）。 */
    public static final String UNKNOWN = "这个操作我没认出来。";

    /** 按钮触发被限流时的应答。 */
    public static final String TOO_FAST = "点得有点快，稍等一下再试。";

    /** 按钮触发的命令执行失败时的应答（异常已在桥内兜住，不冒到 webhook）。 */
    public static final String FAILED = "没执行成功，稍后再试一次。";

    // ────────────── 确认卡 ──────────────

    /** 确认令牌失效（过期 / 已被使用 / 不是你的卡）——三者刻意共用一句，不向试探者区分。 */
    public static final String CONFIRM_EXPIRED = "这张确认卡失效了——可能超时了，也可能已经用过。重新发一次命令就有新的。";

    /** 取消确认的应答（转瞬气泡）。 */
    public static final String CANCELLED = "取消了。";

    /** 取消确认后卡片的终态文案。 */
    public static final String CANCELLED_CARD = "❌ 已取消。";

    /** 确认后卡片置为终态、命令开始执行时的文案。 */
    public static String confirmRunning(String command) {
        return "✅ 确认了，正在执行 /" + command + "…";
    }

    // ────────────── /menu 面板 ──────────────

    /** 面板「← 返回」按钮文案。 */
    public static final String BACK_LABEL = "← 返回";

    /** 面板按权限过滤后一无所剩时的说明（刻意回复而非静默，见 MenuCommandHandler 的取舍）。 */
    public static final String MENU_NO_PERMISSION = "这个群里暂时没有你能用的功能。私聊我，我告诉你都有哪些。";

    /** 面板主页说明。 */
    public static final String MENU_HOME_HINT = "这些是你在本群能用的功能：\n先点个分类，再点按钮就能用。";

    /** 分类页说明（分类名由调用方给出）。 */
    public static String menuCategoryText(String title) {
        return "「" + title + "」—— 点按钮就能用；需要填参数的命令我会告诉你怎么写。";
    }

    /** 面板/帮助一无所剩时的说明（私聊版）：不再说「私聊我」（那在私聊里是自指），给出路（R2）。 */
    public static final String MENU_NO_PERMISSION_PRIVATE =
            "私聊里暂时没有你能用的功能。到群里发 /help 就能看到群里的功能。";

    /** 面板主页说明（私聊版）。标题逐字「私聊可用的功能」（用户 2026-09-22 拍板）。 */
    public static final String MENU_HOME_HINT_PRIVATE = "这些是私聊可用的功能：\n先点个分类，再点按钮就能用。";

    /** 群限定命令的单列区标题（/help 私聊版、面板主页提示行）。逐字「这些得到群里用」（用户 2026-09-22 拍板）。 */
    public static final String GROUP_BOUND_HEADER = "这些得到群里用：";

    /** 群限定命令的行尾标注（/help 群聊版）——口语档位「得…用」（VOICE 第十节）。 */
    public static final String GROUP_ONLY_TAG = "（得在群里用）";

    /** {@code /help} 标题（含插值）：私聊版逐字「私聊可用的功能」，群聊版维持「本群」口径。 */
    public static String helpTitle(int total, boolean privateChat) {
        return (privateChat ? "私聊可用的功能（共 " : "你在本群能用的功能（共 ") + total + " 条）：";
    }

    /** 私聊面板主页的群限定提示行（含插值），如「这些得到群里用：/teach、/group_tag。」 */
    public static String menuGroupBoundLine(List<String> commands) {
        StringBuilder sb = new StringBuilder(GROUP_BOUND_HEADER);
        for (int i = 0; i < commands.size(); i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append('/').append(commands.get(i));
        }
        return sb.append('。').toString();
    }

    // ────────────── 身份（/whoami 与面板身份行）──────────────

    /** 私聊里的身份回执。 */
    public static String identityPrivate(long userId) {
        return "你的 Telegram 用户 ID 是：\n" + userId
                + "\n\n填白名单（复核人、群管理员）的时候填这个数就行。";
    }

    /** 群内（开关打开时）的身份回执：一并给出本群 ID，便于拼成授权配置项。 */
    public static String identityGroup(long chatId, long userId) {
        return "你的 Telegram 用户 ID 是：\n" + userId
                + "\n本群 ID 是：\n" + chatId
                + "\n\n配本群授权的时候，把这两个数字用冒号连起来（先群 ID、后用户 ID）。";
    }

    /** 群内默认（开关关闭）的身份回执：不暴露明文 ID，只引导去私聊。 */
    public static final String IDENTITY_GROUP_HIDDEN =
            "群里就不显示你的 ID 了，免得全群都看到。\n私聊我发 /whoami 就能看到。";

    /** 面板顶部的身份行。 */
    public static String identityLine(long userId) {
        return "你的 Telegram 用户 ID：" + userId;
    }

    /** /whoami 取不到身份时的兜底（命令链上不该发生，兜底不猜）。 */
    public static final String WHOAMI_UNIDENTIFIED = "没认出你的身份，稍后再试一次。";
}

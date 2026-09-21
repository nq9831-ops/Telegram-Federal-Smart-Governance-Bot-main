package com.tg.heyisheng.bot.core.interaction;

/**
 * 交互层（按钮回调 / 确认卡 / 面板）的**共享文案基元**——{@code *Messages} 家族的第一个落点。
 *
 * <p><b>为什么单独建类</b>：这几条原本在多个类里**各写一份**——{@code UNKNOWN = "未知操作。"}
 * 在 {@code MenuCallbackHandler} 与 {@code CallbackCommandBridge} 里一字不差地重复，
 * 改一处必然漏另一处（本项目「两处各写一份条件就会漂移」的同类教训）。
 * 集中之后，第三步「语气铺开」只需改这里一处。
 *
 * <p><b>措辞冻结</b>：本类的字符串**逐字节**取自搬迁前的原实现（含全角标点与 emoji），
 * 搬迁不得改动一个字节——这正是「重构未改变行为」的证据链：现有逐字断言测试保持绿即证明。
 *
 * <p>规范见 {@code docs/VOICE.md}。
 */
public final class InteractionMessages {

    private InteractionMessages() {
    }

    /** 回调 {@code data} 无法解析时的应答（按钮下方气泡，全群可见）。 */
    public static final String UNKNOWN = "未知操作。";

    /** 按钮触发被限流时的应答。 */
    public static final String TOO_FAST = "操作过于频繁，请稍后再试。";

    /** 按钮触发的命令执行失败时的应答（异常已在桥内兜住，不冒到 webhook）。 */
    public static final String FAILED = "执行失败，请稍后再试。";

    /** 取消确认的应答。 */
    public static final String CANCELLED = "已取消。";

    /** 面板「← 返回」按钮文案。 */
    public static final String BACK_LABEL = "← 返回";
}

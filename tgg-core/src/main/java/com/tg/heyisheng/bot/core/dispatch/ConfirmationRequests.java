package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;

/**
 * 确认卡的「登记」接缝（依赖倒置）。
 *
 * <p><b>为什么是接口而不是直接依赖实现</b>：{@link CommandDispatcher} 住在 {@code core.dispatch}，
 * 而待确认操作的登记/校验（一次性令牌、发起者校验、过期）属交互层。
 * 若直接依赖那边的具体类，两个包就会互相依赖；把契约留在本包、实现留给上层，
 * 依赖保持单向——与 {@code TeachGate} / {@code CreditEventSink} 同一套做法。
 *
 * <p><b>未装配即为 null</b>（{@code CommandDispatcher} 的老构造器不传它）：
 * 此时**不拦截任何命令**，行为与升级前逐字一致。
 */
public interface ConfirmationRequests {

    /** 确认按钮的 action 前缀；与交互层处理器 {@code action()} 必须一致（冲突会在路由构造期失败）。 */
    String CONFIRM_ACTION = "cfm";

    /** 取消按钮的 action 前缀（同上）。 */
    String CANCEL_ACTION = "cfn";

    /**
     * 本次执行是否仍需先确认。
     *
     * @param ctx  执行上下文（实现据此判断「本次是否已确认」与「是否带了操作数」）
     * @param mode 命令声明的确认模式
     * @return {@code true} = 需要先确认（调用方应改为返回确认卡）
     */
    boolean requiresConfirmation(UpdateContext ctx, Confirm mode);

    /**
     * 登记一次待确认操作。
     *
     * @return 一次性令牌；确认卡的按钮 data 里携带它
     */
    String issue(UpdateContext ctx);
}

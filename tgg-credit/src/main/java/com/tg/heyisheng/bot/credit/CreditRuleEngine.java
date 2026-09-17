package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.core.credit.CreditEvent;

/**
 * 信用规则引擎（模块七）：把信用事件映射为分值增量。
 *
 * <p><b>接口先行</b>：本阶段用内置常量规则（{@link BuiltInCreditRules}）实现。
 * 后续要支持"按群配置"或"DB 热更新规则"时，只需替换实现——调用方（{@code CreditService}）不变。
 * 这与 {@code RegexLayer} 从内置规则集逐条迁往 DB 的既定路线一致。
 */
public interface CreditRuleEngine {

    /**
     * 该事件对分值的增量（负数表示扣分，0 表示不改变）。
     *
     * <p>实现须对未知/异常输入返回 0（不扣分）而非抛异常——规则引擎是记账环节，
     * 不该因单条事件数据异常而中断整条信用链路。
     *
     * @param event 信用事件
     * @return 分值增量
     */
    int deltaFor(CreditEvent event);
}

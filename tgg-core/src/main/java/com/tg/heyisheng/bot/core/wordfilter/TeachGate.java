package com.tg.heyisheng.bot.core.wordfilter;

import java.util.Optional;

/**
 * 教学门槛的<b>单条规则</b>（模块九 §10.3）。
 *
 * <p><b>为什么在 {@link TeachEligibility} 之下再分一层</b>：原文的门槛有<b>多条</b>
 * （信用分 ≥400 / 入群时长 ≥30 天 / 无违规），而它们的数据源分布在<b>不同模块</b>——
 * 「无违规」在 {@code tgg-core} 可见，「信用分」在 {@code tgg-credit}（core 处于依赖链底层、
 * <b>物理上看不到</b>）。若只留一个 {@code TeachEligibility} bean，则「由谁实现」必须二选一，
 * 且两个同类型 bean 会直接撞在 {@code NoUniqueBeanDefinitionException} 上。
 *
 * <p>故把每条门槛抽成独立的 {@code TeachGate}：core 装配聚合器
 * （{@link TeachEligibility#composite}）并自带「无违规」那条；上层模块只要注册自己的
 * {@code TeachGate} bean，即<b>自动并入判定</b>，<b>core 无需知道它的存在</b>
 * ——与 {@code CreditEventSink} 同一套依赖倒置。
 *
 * <p><b>未接线即零影响</b>：某条门槛的实现不存在时，它就不参与聚合，行为等同于该条不存在
 * （例如模块七未启用时，「无扣分」门槛自动消失，{@code /teach} 行为与升级前逐字一致）。
 *
 * <p><b>实现方纪律</b>：返回值会<b>原样回显</b>给发命令的管理员，故必须是<b>人话</b>、
 * <b>不含个人数据明细</b>（不要回显具体分值、违规原文等）。
 */
@FunctionalInterface
public interface TeachGate {

    /**
     * 判定该用户能否在本群教学。
     *
     * @param chatId 目标群（负数）
     * @param userId 发起者；为 {@code null} 时表示身份不可识别
     * @return <b>空 = 本条通过</b>；非空 = 拒绝原因（人话，会回显给管理员）
     */
    Optional<String> rejectionFor(long chatId, Long userId);
}

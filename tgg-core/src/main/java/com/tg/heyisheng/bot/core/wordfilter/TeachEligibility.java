package com.tg.heyisheng.bot.core.wordfilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 教学权限门槛（模块九 §10.3）——{@code /teach} 放行前的前置校验。
 *
 * <p><b>为什么是 seam 而不是直接判</b>：原文的门槛含「信用分 ≥400」，而<b>信用分在 {@code tgg-credit}</b>，
 * 而 {@code tgg-core} 处于依赖链底层、<b>物理上看不到它</b>。故 core 只定义「谁来判」，
 * 实际判定由上层（模块七）提供实现——与模块九「联邦标记」用的是同一套思路。
 * <b>单条门槛的接缝是 {@link TeachGate}</b>：core 装配聚合器并自带「无违规」那条，
 * 上层模块注册自己的 gate 即自动并入——这样多条门槛可以各自落在<b>看得到自己数据源</b>的模块里，
 * 而不必在「谁来实现」上二选一。
 *
 * <p><b>默认实现必须放行</b>（{@link #allowAll()}）：未接线时行为与升级前逐字一致；
 * 装配层若想要真实门槛，注入自己的实现即可（见 {@code TggCoreConfiguration}）。
 *
 * <p><b>⚠️ 原文三条门槛在本项目的落实情况（如实记录，不假装齐全）</b>：
 * <ul>
 *   <li><b>信用分 ≥400</b> —— <b>已按本项目口径重映射并实现</b>：400 不可达（个人分区间 [0,150]，
 *       见 {@code CreditThresholds}），用户 2026-09-18 拍板改为「<b>不低于初始分</b>」＝无扣分，
 *       实现见模块七的 {@code NoDeductionTeachGate}。</li>
 *   <li><b>入群时长 ≥30 天</b> —— <b>仍未实现</b>：全仓未持久化成员入群时间
 *       （现有观察期是把截止交给 Telegram 的 {@code untilDate}，本地不存）。
 *       要做需新增个人数据采集，属新功能而非接线，且与「数据最小化」原则有张力。</li>
 *   <li><b>无违规</b> —— <b>已实现</b>：core 可见 {@code sensitive_topic_strikes}，
 *       见 {@code NoViolationTeachGate}。</li>
 * </ul>
 */
@FunctionalInterface
public interface TeachEligibility {

    /**
     * 判断该用户能否在本群教学。
     *
     * @return <b>空 = 允许</b>；非空 = 拒绝原因（会原样回显给管理员，故须是人话且不含个人数据）
     */
    Optional<String> rejectionFor(long chatId, Long userId);

    /** 默认实现：一律放行（未接线时的行为＝升级前）。 */
    static TeachEligibility allowAll() {
        return (chatId, userId) -> Optional.empty();
    }

    /**
     * 把各模块注册的 {@link TeachGate} 聚合成一个判定：<b>全部通过才放行</b>。
     *
     * <p>拒绝时返回<b>所有</b>未通过门槛的原因（以「；」连接）——只报第一条会让管理员
     * 修完一条再撞下一条，而门槛是并列条件，理应一次说清。
     *
     * <p>不引入排序：门槛之间无依赖，放行/拒绝的结论与顺序无关（顺序只影响文案里原因的先后）。
     * 传入空列表＝放行（无门槛＝无限制）。
     */
    static TeachEligibility composite(List<TeachGate> gates) {
        List<TeachGate> snapshot = List.copyOf(gates);
        return (chatId, userId) -> {
            List<String> reasons = new ArrayList<>();
            for (TeachGate gate : snapshot) {
                gate.rejectionFor(chatId, userId).ifPresent(reasons::add);
            }
            return reasons.isEmpty() ? Optional.empty() : Optional.of(String.join("；", reasons));
        };
    }
}

package com.tg.heyisheng.bot.core.wordfilter;

import java.util.Optional;

/**
 * 教学权限门槛（模块九 §10.3）——{@code /teach} 放行前的前置校验。
 *
 * <p><b>为什么是 seam 而不是直接判</b>：原文的门槛含「信用分 ≥400」，而<b>信用分在 {@code tgg-credit}</b>，
 * 而 {@code tgg-core} 处于依赖链底层、<b>物理上看不到它</b>。故 core 只定义「谁来判」，
 * 实际判定由上层（模块七）提供实现——与模块九「联邦标记」用的是同一套思路。
 *
 * <p><b>默认实现必须放行</b>（{@link #allowAll()}）：未接线时行为与升级前逐字一致；
 * 装配层若想要真实门槛，注入自己的实现即可（见 {@code TggCoreConfiguration}）。
 *
 * <p><b>⚠️ 原文三条门槛在本项目的落实情况（如实记录，不假装齐全）</b>：
 * <ul>
 *   <li><b>信用分 ≥400</b> —— <b>不可达</b>：本项目个人信用分区间是 [0,150]，400 永远到不了。
 *       需按本项目口径重映射（如「不低于初始分」＝无扣分），或改信用分模型（代价大，无规格背书）。</li>
 *   <li><b>入群时长 ≥30 天</b> —— <b>无数据源</b>：全仓未持久化成员入群时间
 *       （现有观察期是把截止交给 Telegram 的 {@code untilDate}，本地不存）。
 *       要做需新增个人数据采集，属新功能而非接线，且与「数据最小化」原则有张力——<b>未实现</b>。</li>
 *   <li><b>无违规</b> —— <b>可实现</b>：core 可见 {@code sensitive_topic_strikes}，按它判定。</li>
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
}

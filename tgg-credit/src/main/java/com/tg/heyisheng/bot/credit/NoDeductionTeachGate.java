package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.core.wordfilter.TeachGate;

import java.util.Optional;

/**
 * 「信用良好（无扣分）」教学门槛（模块九 §10.3）——原文「信用分 ≥400」按本项目口径重映射。
 *
 * <p><b>为什么不是原文的「≥400」</b>：本项目<b>个人</b>信用分区间是
 * [{@link CreditService#MIN_SCORE}, {@link CreditService#MAX_SCORE}]（0–150），
 * 400 <b>永远不可达</b>（见 {@code CreditThresholds} 的说明：阈值是设计文档默认值、待校准）。
 * 用户 2026-09-18 拍板重映射为「不低于初始分」＝<b>从未被扣分</b>——最贴近原文
 * 「信用良好者才可教学」的意图，且不引入无规格背书的信用分模型变更。
 *
 * <p><b>为什么实现放这里而不是 core</b>：信用分在 {@code tgg-credit}，而 {@code tgg-core}
 * 处于依赖链底层、<b>物理上看不到它</b>。core 只定义 {@link TeachGate} 接缝并负责聚合，
 * 本类作为 gate bean 由 {@link CreditConfiguration} 在模块七启用时注册——启用即自动并入判定，
 * 不启用则这条门槛根本不存在（{@code /teach} 行为与升级前逐字一致）。
 *
 * <p><b>判定维度是「用户全局」</b>：个人信用分的主体是 userId（不按群），
 * 故本门槛只看 {@link CreditSubjectType#INDIVIDUAL}；{@code chatId} 在判定中用不到——
 * 「信用」是跨群的个人属性，与「无违规」（用户 × 群的按群计数）刻意不同。
 */
public class NoDeductionTeachGate implements TeachGate {

    private final CreditService creditService;

    public NoDeductionTeachGate(CreditService creditService) {
        this.creditService = creditService;
    }

    @Override
    public Optional<String> rejectionFor(long chatId, Long userId) {
        if (userId == null) {
            return Optional.of("无法识别你的身份。");
        }
        int score = creditService.scoreOf(CreditSubjectType.INDIVIDUAL, userId);
        return score >= CreditService.INITIAL_SCORE
                ? Optional.empty()
                : Optional.of("本群教学要求信用良好（你已被扣分）。");
    }
}

package com.tg.heyisheng.bot.core.funds;

import java.math.BigDecimal;

/**
 * 保证金扣款<b>接缝</b>（跨模块）：从指定商家的保证金中扣除一笔款项
 * （用途：模块十二 担保交易的仲裁费兜底 / 赔付）。
 *
 * <p><b>为什么接口在 tgg-core 而不是让消费方直连</b>：保证金实现位于 {@code tgg-listing}，
 * 而消费方（担保交易的罚金兜底链）位于 {@code tgg-escrow}——依赖图为
 * {@code escrow → core, common} 与 {@code listing → core, credit}，<b>escrow 不依赖 listing</b>。
 * 直连会新增一条跨模块边；把"扣保证金"这件事的位置固定在 core、由 listing 提供实现，
 * 与 {@link com.tg.heyisheng.bot.core.credit.CreditEventSink}（接口在 core、实现在 credit）同款范式。
 *
 * <p><b>⚠️ 语义缺口（未决——正式实现前必须先定，Wave 4）</b>：现有保证金扣款不是独立动作，
 * 只作为 {@code MerchantDepositService.settle} 的一部分存在，且 {@code settle} <b>要求保证金处于
 * FROZEN</b>（FROZEN 仅由"商家退出申请受理"产生）。担保交易的罚金场景没有该前置，因此需先回答：
 * <ol>
 *   <li><b>扣款是否需要先冻结</b>？还是新增一条独立的"仲裁扣款"路径（不经 settle）？</li>
 *   <li><b>标识映射</b>：本接口按 {@code tgUserId} 收主体（跨模块通用），而保证金按
 *       {@code merchantId} 记账——{@code tgUserId → merchantId} 的转换由<b>实现方</b>负责
 *       （消费方不该知道 merchantId 的存在）。</li>
 * </ol>
 *
 * <p><b>契约</b>：实现<b>不得吞异常</b>后假装成功。余额不足、状态不符、金额超限等一律以
 * {@link DeductionOutcome#rejected} 明确返回——调用方据此继续走兜底链的下一级
 * （罚金账本 → 联邦基金），而不是把"没扣成"当成"扣成功"。
 */
public interface MarginDeductionGateway {

    /**
     * 从指定 TG 用户对应的商家保证金中扣除 {@code amount}。
     *
     * @param tgUserId 商家（败诉方）的 Telegram userId
     * @param amount   扣除金额（应 &gt; 0）
     * @param reason   扣除理由（必填——与保证金既有纪律"扣除必带理由"一致）
     * @return 扣款结果；{@code deducted=true} 表示账本侧已完成扣除
     */
    DeductionOutcome deduct(long tgUserId, BigDecimal amount, String reason);

    /** 扣款结果：显式区分"扣了"与"没扣成"（后者驱动调用方走兜底链下一级）。 */
    record DeductionOutcome(boolean deducted, BigDecimal deductedAmount, String detail) {

        public static DeductionOutcome ok(BigDecimal amount) {
            return new DeductionOutcome(true, amount, "已从保证金扣除");
        }

        public static DeductionOutcome rejected(String detail) {
            return new DeductionOutcome(false, BigDecimal.ZERO, detail);
        }
    }

    /**
     * 默认实现：接缝未接通（保证金模块未启用，或部署方尚未提供实现）→ <b>一律拒绝</b>。
     *
     * <p><b>为什么 fail-closed 而不是静默通过</b>：兜底链的上一级拿不到钱时，调用方应继续走
     * 下一级；若此处假装成功，罚金就被"免"掉了——资金缺口会被掩盖，且无任何可观测信号。
     */
    static MarginDeductionGateway noop() {
        return (tgUserId, amount, reason) -> DeductionOutcome.rejected("保证金扣款接缝未接通");
    }
}

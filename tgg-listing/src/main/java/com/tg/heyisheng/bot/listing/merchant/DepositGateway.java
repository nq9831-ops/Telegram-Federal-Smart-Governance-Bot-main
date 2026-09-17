package com.tg.heyisheng.bot.listing.merchant;

import java.math.BigDecimal;

/**
 * 保证金链上动作的<b>接入位</b>（设计文档 §3.1）。
 *
 * <p><b>为什么是接口而不是 ton4j 调用</b>：本机无 TON 测试网可达性，任何真实链上交互都无法验证
 * ——把「写进仓库却验不了」的代码放进主线，正是本项目 LESSONS 反复警惕的东西
 * （模块九的 L2/L4 本地模型层走的是同一条路）。因此本接口 + {@link NoopDepositGateway} 默认装配，
 * 真实的 ton4j 实现由部署方提供并自验；TON 合约真正必需的场景是<b>模块十二（担保交易）</b>，
 * 届时此接口直接对接、不浪费。
 *
 * <p><b>账本与状态机不经本接口</b>：保证金金额、状态（{@code PENDING → LOCKED → FROZEN →
 * {REFUNDED | DEDUCTED}}）与流水<b>完整落在本地库</b>，本接口只承载「链上那一半动作」。
 * 因此即使网关是 noop，流程、状态、审计与退还判定也全部真实可测。
 *
 * <p><b>实现契约</b>：
 * <ul>
 *   <li>方法抛异常 = 链上动作失败 → 调用方（{@code MerchantDepositService}）所在事务回滚、
 *       状态不推进（fail-closed：宁可停在原状态，也不留下「状态说已锁仓但链上没有」的裂缝）；</li>
 *   <li>实现<b>不得</b>吞异常后返回（那会把链上失败伪装成成功）。</li>
 * </ul>
 */
public interface DepositGateway {

    /**
     * 锁仓：在链上锁定该商家的保证金。
     *
     * @return 网关引用（链上交易哈希 / 合约地址等），落库到 {@code merchant_deposits.gateway_ref}
     */
    String lock(long merchantId, BigDecimal amount, String currency);

    /** 冻结（退出申请受理，暂停该笔保证金的动用）。 */
    void freeze(long merchantId, String gatewayRef);

    /** 退还指定金额。 */
    void refund(long merchantId, String gatewayRef, BigDecimal amount);

    /** 扣除指定金额（用于赔付）。{@code reason} 必非空——由调用方在进入本接口前校验。 */
    void deduct(long merchantId, String gatewayRef, BigDecimal amount, String reason);
}

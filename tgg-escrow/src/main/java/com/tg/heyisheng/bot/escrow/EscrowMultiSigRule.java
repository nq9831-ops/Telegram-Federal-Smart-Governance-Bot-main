package com.tg.heyisheng.bot.escrow;

import java.util.Set;

/**
 * 担保交易裁决的<b>多签规则</b>——链下侧的单一来源（规格 §13.3 第 11 条）。
 *
 * <p><b>规则</b>：
 * <ul>
 *   <li>裁决签名<b>必须包含联邦仲裁节点</b>——「担保只认联邦」是本项目的核心设计原则；</li>
 *   <li>有效组合：{@code 联邦 + 买方} 或 {@code 联邦 + 卖方}（任二，且必含联邦）；</li>
 *   <li><b>无效</b>：{@code 买方 + 卖方}——买卖双方串通即可自行放款/退款，多签形同虚设。</li>
 * </ul>
 *
 * <p><b>为什么必须独立成类</b>：本项目的既有教训是「校验只写在一个入口，另一个入口就能绕过」
 * （如"不可自审"若只写在 Telegram 命令侧，后台接口便是后门）。多签规则有<b>多个消费端</b>
 * （链下裁决服务、后台裁决接口，Wave 5 还有链上合约），规则必须只有一个定义处、
 * 各端都指向它，并用测试双向钉住。
 *
 * <p><b>与密钥的分工（设计约定，Wave 5 落实）</b>：
 * <ul>
 *   <li>联邦签名复用既有 <b>{@code TGG_CREDIT_PRIVATE_KEY}</b>（Ed25519）——它在本项目里
 *       已经是"节点签名密钥"（模块七用它签处罚令、模块八转发、对端用节点公钥验签），
 *       故<b>不引入第二把密钥</b>（见 {@code FederationProperties} 的说明）；</li>
 *   <li>买方/卖方签名由钱包产生（{@code ton_proof} / TON Connect），验证在 Wave 5；</li>
 *   <li>本类只判「签名方集合是否满足规则」，<b>不判签名真伪</b>——验签是调用方的前提。
 *       职责分离让规则可以纯单测，而验签依赖链上环境。</li>
 * </ul>
 *
 * <p><b>未决项</b>（Wave 5 与合约一起定）：多联邦节点时需几个联邦签名、阈值变更流程、
 * 联邦公钥轮换（合约侧需支持，否则轮换即换合约、迁移在途资金）。
 */
public final class EscrowMultiSigRule {

    /** 多签参与方。 */
    public enum Party {
        /** 联邦仲裁节点（裁决必含）。 */
        FEDERATION,
        /** 买方。 */
        BUYER,
        /** 卖方。 */
        SELLER
    }

    /** 裁决所需的最少签名方数（"2/3"里的 2）。 */
    public static final int REQUIRED_SIGNERS = 2;

    private EscrowMultiSigRule() {
    }

    /**
     * 判定一组签名方是否满足裁决条件。
     *
     * @param signers 已<b>验签通过</b>的参与方集合（验签是调用方的前提）
     * @return {@code true} = 可执行裁决
     */
    public static boolean satisfies(Set<Party> signers) {
        if (signers == null || signers.size() < REQUIRED_SIGNERS) {
            return false;
        }
        return signers.contains(Party.FEDERATION);
    }
}

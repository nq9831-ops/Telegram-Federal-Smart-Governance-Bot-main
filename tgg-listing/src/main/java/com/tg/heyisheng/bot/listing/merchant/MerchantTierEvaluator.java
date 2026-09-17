package com.tg.heyisheng.bot.listing.merchant;

import java.math.BigDecimal;

/**
 * 商家等级评定器（模块六 · 设计文档 §3.4）：由<b>信用分 + 保证金 + 交易流水量</b>判定商家等级。
 *
 * <p><b>为什么不引 RulEuler</b>（设计文档 §11 决策 4）：模块七已经有一套自研的规则表达
 * （{@link com.tg.heyisheng.bot.credit.CreditThresholds} 的纯函数阈值判定），等级规则的规模
 * 远达不到需要通用引擎的程度；引新依赖得不偿失（LESSONS 坑 1/18）。本类是那套风格的同款：
 * 常量阈值 + 纯静态函数，因此不依赖数据库、不依赖时钟，是理想的单测对象。
 *
 * <p><b>判定自高到低、第一个命中即返回</b>：顺序写反会让低等级掩盖高等级
 * ——这与 {@code CreditThresholds.penaltyFor} 的纪律一致（本项目在 {@code RegexLayer}
 * 上踩过「低等级掩盖高风险」的坑）。
 *
 * <p><b>三个条件必须同时满足</b>（分 AND 保证金 AND 流水）：任何单一维度都不足以升档
 * ——高保证金买不来好声誉，高分也补不上保证金缺口。
 *
 * <p>⚠️ <b>以下阈值是设计文档给出的默认值，不是 V5.0 规格</b>（该章节不在仓库；V5.0 §7.1
 * 只规定了商家初始信用分 500）：须在真实商家数据上校准。
 *
 * <p><b>流水量当前恒为 0</b>：交易流水属模块十二（担保交易），本阶段无数据源——传 0 意味着
 * 「无交易依据」，故商家入驻后最高只能到 {@link Tier#BRONZE}。这是刻意的：没有数据就不虚升等级。
 */
public final class MerchantTierEvaluator {

    /** 商家等级，按高低递增。{@link #NONE} = 未达任何等级门槛。 */
    public enum Tier {
        /** 未定级（信用分或保证金不足）。 */
        NONE,
        /** 基础级。 */
        BRONZE,
        /** 优质级。 */
        SILVER,
        /** 顶级。 */
        GOLD
    }

    /** GOLD 门槛：信用分。 */
    public static final int GOLD_MIN_SCORE = 120;
    /** GOLD 门槛：保证金金额。 */
    public static final BigDecimal GOLD_MIN_DEPOSIT = new BigDecimal("1000");
    /** GOLD 门槛：交易流水量。 */
    public static final long GOLD_MIN_VOLUME = 100;

    /** SILVER 门槛：信用分。 */
    public static final int SILVER_MIN_SCORE = 90;
    /** SILVER 门槛：保证金金额。 */
    public static final BigDecimal SILVER_MIN_DEPOSIT = new BigDecimal("500");
    /** SILVER 门槛：交易流水量。 */
    public static final long SILVER_MIN_VOLUME = 30;

    /** BRONZE 门槛：信用分。 */
    public static final int BRONZE_MIN_SCORE = 60;
    /** BRONZE 门槛：保证金金额。 */
    public static final BigDecimal BRONZE_MIN_DEPOSIT = new BigDecimal("100");

    private MerchantTierEvaluator() {
    }

    /**
     * 评定商家等级。
     *
     * @param creditScore       商家信用分（{@code CreditSubjectType.MERCHANT} 账本行）
     * @param depositAmount     保证金金额；{@code null} 视为 0（无保证金不可能定级）
     * @param transactionVolume 交易流水量；负数视为 0
     * @return 命中的最高等级；未达门槛返回 {@link Tier#NONE}
     */
    public static Tier evaluate(int creditScore, BigDecimal depositAmount, long transactionVolume) {
        BigDecimal deposit = depositAmount == null ? BigDecimal.ZERO : depositAmount;
        long volume = Math.max(0, transactionVolume);

        if (creditScore >= GOLD_MIN_SCORE
                && deposit.compareTo(GOLD_MIN_DEPOSIT) >= 0
                && volume >= GOLD_MIN_VOLUME) {
            return Tier.GOLD;
        }
        if (creditScore >= SILVER_MIN_SCORE
                && deposit.compareTo(SILVER_MIN_DEPOSIT) >= 0
                && volume >= SILVER_MIN_VOLUME) {
            return Tier.SILVER;
        }
        if (creditScore >= BRONZE_MIN_SCORE && deposit.compareTo(BRONZE_MIN_DEPOSIT) >= 0) {
            return Tier.BRONZE;
        }
        return Tier.NONE;
    }
}

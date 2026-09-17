package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.listing.merchant.MerchantTierEvaluator.Tier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商家等级评定单测（纯函数，不触库、不依赖时钟）。
 *
 * <p>本类守两件事：
 * <ol>
 *   <li><b>三个条件必须同时满足</b>——高保证金不能补低信用分，高信用分不能补保证金缺口，
 *       没有流水也升不到 SILVER 以上（单一维度不该能升档）；</li>
 *   <li><b>自高到低命中即返回</b>——同时满足多档时必须给最高的那档，
 *       顺序写反会让低等级掩盖高等级。</li>
 * </ol>
 * 边界值逐个钉住（恰好等于阈值算命中，差一分/一分钱/一次流水即落档）——阈值判定最容易
 * 在临界点上出错，而它不依赖任何外部状态，正是单测该覆盖的地方。
 */
class MerchantTierEvaluatorTest {

    private static Tier evaluate(int score, String deposit, long volume) {
        return MerchantTierEvaluator.evaluate(score, new BigDecimal(deposit), volume);
    }

    @Test
    void goldWhenAllThreeThresholdsMet() {
        assertThat(evaluate(120, "1000", 100)).isEqualTo(Tier.GOLD);
    }

    @Test
    void goldThresholdsAreInclusiveOnTheBoundary() {
        assertThat(evaluate(MerchantTierEvaluator.GOLD_MIN_SCORE,
                MerchantTierEvaluator.GOLD_MIN_DEPOSIT.toPlainString(),
                MerchantTierEvaluator.GOLD_MIN_VOLUME))
                .as("恰好等于门槛应命中——阈值是「达到」而不是「超过」")
                .isEqualTo(Tier.GOLD);
    }

    @Test
    void onePointShortOfGoldFallsToSilverWhenSilverQualifies() {
        assertThat(evaluate(MerchantTierEvaluator.GOLD_MIN_SCORE - 1, "1000", 100))
                .isEqualTo(Tier.SILVER);
    }

    @Test
    void oneCentShortOfGoldDepositFallsToSilver() {
        assertThat(evaluate(120, "999.99999999", 100))
                .as("BigDecimal 精确比较：不能因为浮点误差把 999.99999999 当成 1000")
                .isEqualTo(Tier.SILVER);
    }

    @Test
    void oneTradeShortOfGoldVolumeFallsToSilver() {
        assertThat(evaluate(120, "1000", 99)).isEqualTo(Tier.SILVER);
    }

    @Test
    void silverWhenSilverThresholdsMetExactly() {
        assertThat(evaluate(MerchantTierEvaluator.SILVER_MIN_SCORE,
                MerchantTierEvaluator.SILVER_MIN_DEPOSIT.toPlainString(),
                MerchantTierEvaluator.SILVER_MIN_VOLUME))
                .isEqualTo(Tier.SILVER);
    }

    @Test
    void bronzeWhenOnlyBaseThresholdsMet() {
        assertThat(evaluate(MerchantTierEvaluator.BRONZE_MIN_SCORE,
                MerchantTierEvaluator.BRONZE_MIN_DEPOSIT.toPlainString(), 0))
                .isEqualTo(Tier.BRONZE);
    }

    @Test
    void highScoreAndDepositWithoutVolumeStillOnlyBronze() {
        assertThat(evaluate(500, "5000", 0))
                .as("没有交易流水就没有升级依据——不应因「分高钱多」直接跳到 SILVER/GOLD")
                .isEqualTo(Tier.BRONZE);
    }

    @Test
    void noneWhenScoreBelowBronzeThreshold() {
        assertThat(evaluate(MerchantTierEvaluator.BRONZE_MIN_SCORE - 1, "5000", 500))
                .as("低信用分不能靠保证金与流水补")
                .isEqualTo(Tier.NONE);
    }

    @Test
    void noneWhenDepositBelowBronzeThreshold() {
        assertThat(evaluate(500, "99", 500))
                .as("保证金不足不能靠信用分与流水补")
                .isEqualTo(Tier.NONE);
    }

    @Test
    void nullDepositIsTreatedAsNoDeposit() {
        assertThat(MerchantTierEvaluator.evaluate(500, null, 500)).isEqualTo(Tier.NONE);
    }

    @Test
    void negativeVolumeIsTreatedAsZero() {
        assertThat(evaluate(500, "5000", -10))
                .as("负数流水按 0 处理，不得绕过门槛")
                .isEqualTo(Tier.BRONZE);
    }

    @Test
    void zeroScoreYieldsNone() {
        assertThat(evaluate(0, "1000", 100)).isEqualTo(Tier.NONE);
    }
}

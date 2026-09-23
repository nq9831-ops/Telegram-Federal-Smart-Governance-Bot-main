package com.tg.heyisheng.bot.escrow;

import com.tg.heyisheng.bot.escrow.EscrowMultiSigRule.Party;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 裁决多签规则的守卫（规格 §13.3 第 11 条的三条规则逐条钉住）。
 *
 * <p>其中 {@link #buyerPlusSellerIsRejected()} 是<b>安全关键</b>用例：若规则退化为
 * "任意两方即可"，买卖双方串通就能自行放款——多签机制完全失效，而系统不会有任何报错。
 */
class EscrowMultiSigRuleTest {

    @Test
    void federationPlusBuyerIsValid() {
        assertThat(EscrowMultiSigRule.satisfies(Set.of(Party.FEDERATION, Party.BUYER))).isTrue();
    }

    @Test
    void federationPlusSellerIsValid() {
        assertThat(EscrowMultiSigRule.satisfies(Set.of(Party.FEDERATION, Party.SELLER))).isTrue();
    }

    @Test
    void buyerPlusSellerIsRejected() {
        assertThat(EscrowMultiSigRule.satisfies(Set.of(Party.BUYER, Party.SELLER)))
                .as("买卖双方联合签名必须无效——否则串通即可自行放款，多签形同虚设")
                .isFalse();
    }

    @Test
    void federationAloneIsInsufficient() {
        assertThat(EscrowMultiSigRule.satisfies(Set.of(Party.FEDERATION)))
                .as("单方签名不足 2/3 的阈值")
                .isFalse();
    }

    @Test
    void allThreePartiesIsValid() {
        assertThat(EscrowMultiSigRule.satisfies(Set.of(Party.FEDERATION, Party.BUYER, Party.SELLER)))
                .isTrue();
    }

    @Test
    void emptyOrNullIsRejected() {
        assertThat(EscrowMultiSigRule.satisfies(Set.of())).isFalse();
        assertThat(EscrowMultiSigRule.satisfies(null)).isFalse();
    }

    @Test
    void everyTwoPartyCombinationIsCoveredByTheRule() {
        // 穷举 3 方里的全部两方组合，逐一对表——防止将来加参与方时规则被悄悄放宽
        Set<Set<Party>> pairs = Stream.of(Party.values())
                .flatMap(a -> Stream.of(Party.values())
                        .filter(b -> a != b)
                        .map(b -> Set.of(a, b)))
                .collect(Collectors.toSet());

        assertThat(pairs).hasSize(3);
        for (Set<Party> pair : pairs) {
            boolean expected = pair.contains(Party.FEDERATION);
            assertThat(EscrowMultiSigRule.satisfies(pair))
                    .as("两方组合 %s 的判定", pair)
                    .isEqualTo(expected);
        }
    }
}

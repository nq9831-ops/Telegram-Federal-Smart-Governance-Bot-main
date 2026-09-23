package com.tg.heyisheng.bot.escrow;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资金流水实体的不变式守卫。
 *
 * <p>它守的是资金账本最基础的三条：<b>方向必填</b>（否则对账时不知道钱往哪走）、
 * <b>金额必正</b>（方向由 {@code direction} 表达，不靠符号——否则一条 −100 的 HOLD
 * 会让"进账"与"出账"在某处被静默反转）、<b>幂等键必填</b>（无键则无法保证不重复入账）。
 */
class EscrowFundLedgerTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    private static EscrowFundLedger valid() {
        return new EscrowFundLedger(1L, EscrowFundLedger.Direction.HOLD, new BigDecimal("100.00000000"),
                "USDT", 42L, "托管", "escrow:1:hold", NOW);
    }

    @Test
    void startsInLocalChainStateAndKeepsDirectionAsName() {
        EscrowFundLedger ledger = valid();

        assertThat(ledger.getDirection()).isEqualTo("HOLD");
        assertThat(ledger.getChainState())
                .as("未上链时链上态恒为 LOCAL——链上接入（Wave 5）前不得出现其他取值")
                .isEqualTo(EscrowFundLedger.CHAIN_LOCAL);
        assertThat(ledger.getChainRef()).isNull();
        assertThat(ledger.getAmount()).isEqualByComparingTo("100.00000000");
    }

    @Test
    void rejectsNullDirection() {
        assertThatThrownBy(() -> new EscrowFundLedger(1L, null, BigDecimal.ONE,
                "USDT", 42L, null, "k", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("方向");
    }

    @Test
    void rejectsZeroAndNegativeAmount() {
        assertThatThrownBy(() -> new EscrowFundLedger(1L, EscrowFundLedger.Direction.REFUND,
                BigDecimal.ZERO, "USDT", 42L, null, "k", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EscrowFundLedger(1L, EscrowFundLedger.Direction.REFUND,
                new BigDecimal("-1"), "USDT", 42L, null, "k", NOW))
                .as("金额恒正——方向由 direction 表达，负号会与方向语义打架")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankIdempotencyKey() {
        assertThatThrownBy(() -> new EscrowFundLedger(1L, EscrowFundLedger.Direction.HOLD,
                BigDecimal.ONE, "USDT", 42L, null, "  ", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("幂等键");
    }
}

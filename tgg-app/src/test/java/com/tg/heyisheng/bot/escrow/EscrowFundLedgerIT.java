package com.tg.heyisheng.bot.escrow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 担保交易资金流水账的<b>真库</b>行为验证。
 *
 * <p><b>为什么必须是真库</b>：这是资金账本，幂等（同键不重复入账）不是"锦上添花"而是核心性质。
 * 单元测试与编译通过都证明不了 {@code INSERT IGNORE} 在<b>本表唯一约束</b>上真的生效——
 * 唯一键写错、列名不匹配、约束未建，都会让第二次插入<b>照样成功</b>，而资金被重复入账时
 * 没有任何一方会报错（静默失效）。
 *
 * <p>该测试走 failsafe（{@code mvn verify}），需要真实 MySQL（本仓 {@code tgg_test} 库）。
 *
 * <p><b>为什么类级 {@code @Transactional}</b>：{@code insertIfAbsent} 是 {@code @Modifying}
 * native 查询，Spring Data 要求它在事务内执行（否则抛
 * {@code InvalidDataAccessApiUsage: Executing an update/delete query}）。这是<b>契约</b>——
 * 调用方（资金服务）必须自带事务，与 {@code CreditService.apply} 的 {@code @Transactional} 同理。
 * 测试级事务在用例结束回滚，不留测试数据。
 */
@SpringBootTest
@Transactional
class EscrowFundLedgerIT {

    @DynamicPropertySource
    static void enableEscrow(DynamicPropertyRegistry registry) {
        registry.add("tgg.escrow.enabled", () -> "true");
    }

    @Autowired
    private EscrowFundLedgerRepository ledger;

    @Test
    void insertIfAbsentIsIdempotentOnRepeatedKey() {
        long orderId = 987654321L;
        String key = "it:escrow:fund:" + System.nanoTime();
        Instant now = Instant.now();

        int first = ledger.insertIfAbsent(orderId, "HOLD", new BigDecimal("100.00000000"), "USDT",
                42L, null, "LOCAL", "集成测试", key, now);
        int second = ledger.insertIfAbsent(orderId, "HOLD", new BigDecimal("100.00000000"), "USDT",
                42L, null, "LOCAL", "集成测试", key, now);

        assertThat(first).as("首次写入应影响 1 行").isEqualTo(1);
        assertThat(second)
                .as("同幂等键第二次必须被静默跳过（0 行）——否则资金会重复入账，且无任何报错")
                .isZero();

        List<EscrowFundLedger> rows = ledger.findByOrderIdOrderByIdAsc(orderId);
        assertThat(rows).as("同一订单只应有一条该键的流水").hasSize(1);
        assertThat(rows.get(0).getAmount()).isEqualByComparingTo("100.00000000");
        assertThat(rows.get(0).getChainState())
                .as("未上链时链上态为 LOCAL（V25 默认值）")
                .isEqualTo("LOCAL");
    }

    @Test
    void readBackBySubjectUserFindsTheRow() {
        long userId = 555000111L;
        String key = "it:escrow:subject:" + System.nanoTime();

        ledger.insertIfAbsent(null, "DEDUCT_PENALTY", new BigDecimal("5.50000000"), "USDT",
                userId, null, "LOCAL", "罚金兜底（集成测试）", key, Instant.now());

        assertThat(ledger.findBySubjectUserIdOrderByIdAsc(userId))
                .as("查询侧入口必须能查到刚写入的流水（否则账本能写不能读）")
                .anyMatch(row -> key.equals(row.getIdempotencyKey()));
    }
}

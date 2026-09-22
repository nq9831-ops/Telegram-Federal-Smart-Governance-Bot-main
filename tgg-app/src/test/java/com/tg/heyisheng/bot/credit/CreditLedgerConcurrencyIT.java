package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.credit.CreditEvent;
import com.tg.heyisheng.bot.core.credit.CreditEventType;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 信用分**流水**在并发下的连续性（模块七）。
 *
 * <p><b>守的是什么</b>：账本分值本身由库侧 {@code GREATEST/LEAST} 原子夹取保证不丢更新，但流水的
 * {@code score_before} 是**应用侧先读**出来的。同一主体的并发事件若那次读不加锁，就会读到同一个前值
 * ⇒ 流水出现重复 before ⇒ 账本串不成连续轨迹（审计断裂）。
 *
 * <p><b>修法</b>：前值改走 {@code CreditScoreRepository.findForUpdate}（{@code SELECT ... FOR UPDATE}），
 * 同一主体的事件在「读前值」处即串行化。锁粒度是「该主体一行」，且只在**命中改分**时发生（不是每条消息）。
 *
 * <p><b>判别式为何用「集合相等」</b>：串行化后 6 条流水的前值必然是 {@code 100,85,70,55,40,25} 恰好各一次
 * （后值 {@code 85..10}）。一旦并发重复读，集合里就会出现重复项、缺项 ⇒ 直接失败。这样断言与线程完成顺序
 * 无关，不受调度差异影响。
 *
 * <p><b>关于 RED 的诚实说明（重要，未被解决）</b>：本测试的闸门只能卡在「读前值**之前**」
 * （{@code CreditService.apply} 里 {@code clock.instant()} 的位置），无法精确卡在「读与写之间」；
 * 再叠加另两条**环境性串行化**，导致修复前的复现是**概率性**的：
 * <ol>
 *   <li>测试数据源 Hikari {@code maximum-pool-size: 2}（见 tgg-app/src/test/resources/application.yml）
 *       —— 线程数超过 2 时它们会在连接池上排队，并发度实际受限于 2；</li>
 *   <li>{@code INSERT IGNORE} 命中重复键时会做重复键检查（取共享锁），本身也带来部分串行化。</li>
 * </ol>
 * 实测：修复前曾观测到一轮「两条流水 before 均为 100」的断裂；但随后 2 线程/6 线程、
 * 「从零建行」/「预置已提交行」四种组合**都未能再次复现**。故本测试的定位是：
 * <b>对正确实现的稳定门禁（必绿）</b>，而非稳定的 RED 判别器。
 * 要做成确定性 RED，需要在源码里给出「读与写之间」的可注入接缝——属待办，见 KNOWN-ISSUES。
 */
@SpringBootTest
class CreditLedgerConcurrencyIT {

    private static final int THREADS = 6;
    /** MEDIUM 档的扣分（见 BuiltInCreditRules）：-15。 */
    private static final int DELTA = -15;

    /** 用 CyclicBarrier 把线程卡在 {@code clock.instant()} 上（即「读前值」之前的那条缝）。 */
    private static final class BarrierClock extends Clock {
        private final AtomicBoolean armed = new AtomicBoolean(false);
        private final CyclicBarrier barrier = new CyclicBarrier(THREADS);

        void arm() {
            armed.set(true);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (armed.get()) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 超时/中断也继续——让断言（而非死锁）暴露问题
                }
            }
            return Instant.now();
        }
    }

    private static final long UID = 900_000_000L + (System.nanoTime() % 100_000L);

    @Autowired
    private CreditScoreRepository scores;

    @Autowired
    private CreditEventRecordRepository events;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    private final BarrierClock clock = new BarrierClock();

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM credit_events WHERE subject_id = ?", UID);
        jdbc.update("DELETE FROM credit_scores WHERE subject_id = ?", UID);
    }

    @Test
    void concurrentEventsForSameSubjectProduceAChainableLedger() throws Exception {
        CreditService service = new CreditService(
                new BuiltInCreditRules(), scores, events, IdHasher.fromEnvironment(), clock);

        // 预置账本行（**已提交**，走 JdbcTemplate 自动提交）。
        // 为什么必须预置：若让各线程自己去建行，`INSERT IGNORE` 的**排他插入锁**会顺带把并发串行化，
        // 竞态窗口根本打不开（实测：2 线程/6 线程「从零建行」都不复现）。
        // 预置后 INSERT IGNORE 退化为重复键检查（只取共享锁，互不阻塞）⇒ 并发真的挤进「读前值」这一步。
        jdbc.update("INSERT INTO credit_scores (subject_type, subject_id, score, updated_at) VALUES (?, ?, ?, ?)",
                "INDIVIDUAL", UID, CreditService.INITIAL_SCORE, Timestamp.from(Instant.now()));

        clock.arm();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String key = "conc-it:" + UID + ":" + i;
            Thread t = new Thread(() -> applyInTx(service, key));
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join(20_000);
        }

        List<CreditEventRecord> rows = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            rows.add(events.findByIdempotencyKey("conc-it:" + UID + ":" + i).orElseThrow());
        }

        List<Integer> befores = rows.stream().map(CreditEventRecord::getScoreBefore).sorted().toList();
        List<Integer> afters = rows.stream().map(CreditEventRecord::getScoreAfter).sorted().toList();

        List<Integer> expectedBefores = new ArrayList<>();
        List<Integer> expectedAfters = new ArrayList<>();
        for (int k = 0; k < THREADS; k++) {
            expectedBefores.add(CreditService.INITIAL_SCORE + k * DELTA);
            expectedAfters.add(CreditService.INITIAL_SCORE + (k + 1) * DELTA);
        }
        expectedBefores.sort(Integer::compareTo);
        expectedAfters.sort(Integer::compareTo);

        assertThat(befores)
                .as("流水前值必须恰好构成一条递减链（并发不加锁时会重复读同一前值 ⇒ 集合出现重复/缺项）")
                .isEqualTo(expectedBefores);
        assertThat(afters)
                .as("流水后值同理")
                .isEqualTo(expectedAfters);

        assertThat(service.scoreOf(CreditSubjectType.INDIVIDUAL, UID))
                .as("%d 条 MEDIUM(%d) 各扣一次", THREADS, DELTA)
                .isEqualTo(CreditService.INITIAL_SCORE + THREADS * DELTA);
    }

    /** 单条事件在**自己的事务**里记账（service 是手工 new 的，@Transactional 不生效）。 */
    private void applyInTx(CreditService service, String idempotencyKey) {
        new TransactionTemplate(txManager).execute(status -> {
            service.apply(CreditEvent.of(CreditSubjectType.INDIVIDUAL, UID,
                    CreditEventType.MODERATION_HIT, RiskLevel.MEDIUM, false, "conc-it",
                    false, idempotencyKey));
            return null;
        });
    }
}

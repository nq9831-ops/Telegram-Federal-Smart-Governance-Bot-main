package com.tg.heyisheng.bot.breach;

import com.tg.heyisheng.bot.core.breach.DataBreachIncident;
import com.tg.heyisheng.bot.core.breach.DataBreachRepository;
import com.tg.heyisheng.bot.core.breach.DataBreachService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 泄露事件「已通报」标记的 <b>并发原子性</b>（模块十 §11.2）。
 *
 * <p>守的重点：首报时间是<b>合规证据</b>。两个复核人同时标记时，读-改-写实现会让两者都判定
 * 「我是首个」、后写者覆盖先写者的首报时间。
 *
 * <p>本测试用<b>闸门时钟</b>把两线程稳定卡在「判定之后、写入之前」——顺序调用测不出该竞态
 * （第二次调用本就该失败），只有并发 + 闸门才能确定性地暴露它。
 */
@SpringBootTest
class DataBreachAtomicIT {

    /** 用 CyclicBarrier 把线程卡在 {@code clock.instant()} 上（即读写之间的那条缝）。 */
    private static final class BarrierClock extends Clock {
        private final AtomicBoolean armed = new AtomicBoolean(false);
        private final CyclicBarrier barrier = new CyclicBarrier(2);

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
                    // 超时/中断也继续执行——让断言（而非死锁）暴露问题
                }
            }
            return Instant.now();
        }
    }

    @Autowired
    private DataBreachRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private final BarrierClock clock = new BarrierClock();
    private volatile long incidentId;

    @AfterEach
    void cleanup() {
        if (incidentId != 0) {
            jdbcTemplate.update("DELETE FROM data_breach_incident WHERE id = ?", incidentId);
            incidentId = 0;
        }
    }

    @Test
    void concurrentMarkReportedHasExactlyOneWinnerAndKeepsFirstTimestamp() throws Exception {
        DataBreachService service = new DataBreachService(repository, clock);
        DataBreachIncident saved = service.register(null, "并发标记用例", 1, 1L);
        incidentId = saved.getId();

        AtomicInteger successes = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2);
        clock.arm();
        for (long operator : new long[]{900L, 901L}) {
            new Thread(() -> {
                try {
                    // service 是手工 new 的（非 Spring 代理），@Transactional 不生效 —— 故显式开事务：
                    // 这也正好让两线程各持一个真事务，构成真实并发。
                    new TransactionTemplate(txManager).execute(status -> {
                        if (service.markReported(incidentId, operator)) {
                            successes.incrementAndGet();
                        }
                        return null;
                    });
                } finally {
                    done.countDown();
                }
            }).start();
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).as("两线程应在超时前完成").isTrue();

        assertThat(successes.get())
                .as("并发标记只允许一个赢家——否则先写者的首报时间被后写者覆盖")
                .isEqualTo(1);
        DataBreachIncident after = repository.findById(incidentId).orElseThrow();
        assertThat(after.getReportedAt()).as("首报时间已写入且未被清空").isNotNull();
        assertThat(after.getReportedBy()).as("保留赢家的操作者 id").isIn(900L, 901L);
    }
}

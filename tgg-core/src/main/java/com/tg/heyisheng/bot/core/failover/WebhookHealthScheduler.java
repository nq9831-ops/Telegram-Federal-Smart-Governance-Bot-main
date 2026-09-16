package com.tg.heyisheng.bot.core.failover;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时探测 webhook 健康，必要时触发降级。
 *
 * <p>间隔 × 阈值 ≤ 30 秒即满足「30 秒内切换」：默认 10 秒 × 3 次 = 30 秒。
 * 两个值都可通过配置调整（`tgg.failover.probe-interval-ms` / `failure-threshold`）。
 *
 * <p>探测抛出的异常在这里被吞掉并记录——单次探测失败不应中断调度，
 * 更不应把调度线程搞死（那会导致降级机制静默失效）。
 */
public class WebhookHealthScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebhookHealthScheduler.class);

    private final PollingFallbackCoordinator coordinator;

    public WebhookHealthScheduler(PollingFallbackCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${tgg.failover.probe-interval-ms:10000}")
    public void probe() {
        try {
            coordinator.probeOnce();
        } catch (Exception ex) {
            log.warn("健康探测执行异常（忽略本次，等待下次探测）", ex);
        }
    }
}

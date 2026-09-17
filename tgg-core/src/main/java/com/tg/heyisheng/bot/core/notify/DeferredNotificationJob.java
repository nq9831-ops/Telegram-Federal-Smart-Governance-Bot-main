package com.tg.heyisheng.bot.core.notify;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * 延迟通知冲刷任务（模块十 §11.1）——把「免打扰时段已结束」的暂存通知发出去。
 *
 * <p><b>为什么用 {@code @Scheduled} 而不是消息队列</b>：原文写 Kafka 异步，但本项目明确不引 Kafka
 * （用限速 + 分批 + {@code @Scheduled} 替代，见 HANDOFF 的非目标）。一个每分钟一次的轻量扫描
 * 足以满足「时段结束后发送」，不值得为它引入一套消息基础设施。
 *
 * <p>间隔可配（{@code tgg.notify.flush-interval-ms}）；无暂存记录时该查询几乎无成本。
 */
public class DeferredNotificationJob {

    private final DeferredNotificationFlusher flusher;

    public DeferredNotificationJob(DeferredNotificationFlusher flusher) {
        this.flusher = flusher;
    }

    @Scheduled(fixedDelayString = "${tgg.notify.flush-interval-ms:60000}")
    public void flushDeferred() {
        flusher.flushDue();
    }
}

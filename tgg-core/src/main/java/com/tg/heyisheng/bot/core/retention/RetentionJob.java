package com.tg.heyisheng.bot.core.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 保留策略每日任务（模块十 §11.2）。
 *
 * <p><b>报告始终输出</b>：即便未启用清理（默认），也把「哪些数据超期、各多少行」打进日志
 * ——运维需要先看到会发生什么，才谈得上放行（{@code tgg.retention.enabled=true}）。
 * 若只在启用时才输出，运维就没有做决定的依据。
 *
 * <p>时间默认 04:30，避开收录验证任务（03:00）。
 */
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final RetentionService retention;

    public RetentionJob(RetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(cron = "${tgg.retention.cron:0 30 4 * * *}")
    public void run() {
        for (RetentionService.Finding finding : retention.report()) {
            if (finding.purgeable()) {
                log.info("保留策略报告：{}.{} → 超期 {} 行（可清理{}{}）",
                        finding.table(), finding.rule(), finding.expired(),
                        finding.note() == null ? "" : "；",
                        finding.note() == null ? "" : finding.note());
            } else {
                log.info("保留策略报告：{} → 不可清理（{}）", finding.table(), finding.note());
            }
        }
        long deleted = retention.purge();
        if (deleted < 0) {
            log.info("保留策略为「只报告」模式（tgg.retention.enabled=false）——未删除任何数据");
        }
    }
}

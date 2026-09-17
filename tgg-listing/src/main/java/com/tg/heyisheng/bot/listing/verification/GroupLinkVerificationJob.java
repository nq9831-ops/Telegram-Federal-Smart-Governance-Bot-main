package com.tg.heyisheng.bot.listing.verification;

import com.tg.heyisheng.bot.listing.ListingGroup;
import com.tg.heyisheng.bot.listing.ListingGroupService;
import com.tg.heyisheng.bot.listing.ListingProperties;
import com.tg.heyisheng.bot.listing.notify.SubmitterNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.List;

/**
 * 模块五 · 链接验证定时任务（设计文档 §3.2、§6.2）。
 *
 * <p>每日凌晨对 {@code status=ACTIVE} 的收录条目逐个探测：
 * <ul>
 *   <li>{@code FAIL} → 按 {@code tgg.listing.retry-times}（默认 2）重试，
 *       间隔 {@code tgg.listing.retry-interval-minutes}（默认 5 分钟）；</li>
 *   <li>{@code ERROR} → <b>不重试</b>：它表示探测层本身不可用（无 token / 网络不通 / 限流），
 *       再等 5 分钟通常还是同样的结论，只会把任务拖长；且它本就不影响 {@code failCount}
 *       （见 {@link VerificationResult} 与 {@code ListingGroupService#recordOutcome}）。</li>
 * </ul>
 *
 * <p><b>可测性</b>：等待经 {@link Sleeper} 注入（测试断言「退了避、间隔就是配置值」而无需真等），
 * 时钟在服务层经 {@link java.time.Clock} 注入（测试可断言 {@code suspendedAt} 的确切时刻）。
 *
 * <p><b>cron 占位符带默认值</b>：{@code @Scheduled} 的 {@code ${...}} 由 Environment 解析，
 * 而 {@link ListingProperties} 的字段默认值<b>不会</b>写进 Environment——不带默认值的话，
 * 「启用了模块但没在配置里写 verify-cron」会导致上下文启动失败。
 *
 * <p><b>通知放在这里而不是服务里</b>：{@code ListingGroupService#recordOutcome} 的布尔返回值
 * 就是「本次判失效，供调用方发通知」的语义；调用方是本任务。放在任务侧还有个时序上的好处——
 * 服务的事务此时已提交，通知不可能先于「下架已落库」发生。通知失败<b>不得</b>影响下架结果
 * （见 {@link SubmitterNotifier} 契约），故此处另有一层兜底捕获。
 */
public class GroupLinkVerificationJob {

    private static final Logger log = LoggerFactory.getLogger(GroupLinkVerificationJob.class);

    private final ListingGroupService service;
    private final ListingProperties properties;
    private final Sleeper sleeper;
    private final SubmitterNotifier notifier;

    public GroupLinkVerificationJob(ListingGroupService service,
                                    ListingProperties properties,
                                    Sleeper sleeper,
                                    SubmitterNotifier notifier) {
        this.service = service;
        this.properties = properties;
        this.sleeper = sleeper;
        this.notifier = notifier;
    }

    /** 全库扫描一轮。单条异常不阻断其余条目——收录库里一条坏数据的探针故障不该让整轮验证停摆。 */
    @Scheduled(cron = "${tgg.listing.verify-cron:0 0 3 * * *}")
    public void verifyAllActive() {
        List<ListingGroup> active = service.activeGroups();
        if (active.isEmpty()) {
            log.debug("收录库无待验证条目，本轮跳过。");
            return;
        }

        int suspended = 0;
        for (ListingGroup entry : active) {
            try {
                VerificationResult result = verifyWithRetry(entry);
                if (service.recordOutcome(entry, result)) {
                    suspended++;
                    notifySubmitter(entry);
                }
            } catch (RuntimeException ex) {
                log.error("条目 #{} 的验证流程异常，跳过本条（不影响其余条目）。", entry.getId(), ex);
            }
        }
        log.info("链接验证完成：本轮校验 {} 条，新增失效（SUSPENDED）{} 条。", active.size(), suspended);
    }

    /**
     * 通知提交者「其收录已下架」。
     *
     * <p>两层防护：{@link SubmitterNotifier} 的实现本身须吞异常（接口契约），
     * 这里再兜一层——通知通道是外部依赖（Bot API），它的任何异常都不该让
     * 「已经落库的下架结果」变成「本轮验证失败」。
     */
    private void notifySubmitter(ListingGroup entry) {
        try {
            notifier.notifyDelisted(entry);
        } catch (RuntimeException ex) {
            log.error("条目 #{} 的下架通知投递异常，已忽略（下架结果不受影响）。", entry.getId(), ex);
        }
    }

    /** 只在「真失效」（FAIL）时退避重试；重试后仍需落在同一条判定上。 */
    private VerificationResult verifyWithRetry(ListingGroup entry) {
        VerificationResult result = service.verify(entry);
        for (int attempt = 0;
             attempt < properties.getRetryTimes() && result == VerificationResult.FAIL;
             attempt++) {
            sleeper.sleep(Duration.ofMinutes(properties.getRetryIntervalMinutes()));
            result = service.verify(entry);
        }
        return result;
    }
}

package com.tg.heyisheng.bot.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 延迟通知冲刷器（模块十 §11.1）——免打扰时段结束后把暂存的通知发出去。
 *
 * <p><b>逐用户判定</b>：不同用户的免打扰时段不同，故按用户分组、逐个判断「此刻是否仍在静默」，
 * 仍在静默的继续留着——不能按全局时刻一刀切，否则先醒的人会被后睡的人拖住。
 *
 * <p><b>投递后即出队</b>：即便投递时被频率门抑制（计入摘要）也视为已处理——否则同一批通知会被
 * 反复冲刷、永久堆积；被抑制的条数已在该用户该级别的窗口里累计，下次发送时会以摘要形式补上。
 */
public class DeferredNotificationFlusher {

    private static final Logger log = LoggerFactory.getLogger(DeferredNotificationFlusher.class);

    private final DeferredNotificationRepository deferred;
    private final NotificationPreferenceService preferences;
    private final NotificationDispatcher dispatcher;
    private final Clock clock;

    public DeferredNotificationFlusher(DeferredNotificationRepository deferred,
                                       NotificationPreferenceService preferences,
                                       NotificationDispatcher dispatcher,
                                       Clock clock) {
        this.deferred = deferred;
        this.preferences = preferences;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    /**
     * 冲刷所有「已不在免打扰时段」的暂存通知。
     *
     * @return 实际投递条数（被频率门抑制的不计入）
     */
    @Transactional
    public int flushDue() {
        LocalTime now = LocalTime.now(clock);
        Map<Long, List<DeferredNotification>> byUser = deferred.findAllByOrderByIdAsc().stream()
                .collect(Collectors.groupingBy(DeferredNotification::getUserId));

        int sent = 0;
        for (Map.Entry<Long, List<DeferredNotification>> entry : byUser.entrySet()) {
            long userId = entry.getKey();
            if (preferences.isQuietAt(userId, now)) {
                continue; // 该用户仍在免打扰时段，继续留着
            }
            for (DeferredNotification pending : entry.getValue()) {
                if (dispatcher.notify(pending.toNotification())) {
                    sent++;
                }
                deferred.delete(pending);
            }
        }
        if (sent > 0) {
            log.info("延迟通知已冲刷：投递 {} 条", sent);
        }
        return sent;
    }
}

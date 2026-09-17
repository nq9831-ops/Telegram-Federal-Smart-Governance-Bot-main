package com.tg.heyisheng.bot.core.notify;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalTime;

/**
 * 用户通知偏好服务（模块十 §11.1）：设置 / 清除 / 查询免打扰时段。
 *
 * <p><b>未配置 = 不设免打扰</b>：沉默不擅自替用户消音。压住通知比多发一条更容易造成伤害
 * ——用户可能正等着封禁/解封这类结果。
 *
 * <p><b>时区口径</b>：时段按 {@link Clock} 的时区解释（默认 UTC）。跨时区部署须统一服务器 TZ，
 * 已记入部署清单——把「22:00」解释成哪个时区，直接决定通知会不会在半夜响。
 */
@Service
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository repository;
    private final Clock clock;

    @Autowired
    public NotificationPreferenceService(NotificationPreferenceRepository repository) {
        this(repository, Clock.systemUTC());
    }

    NotificationPreferenceService(NotificationPreferenceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 设置免打扰时段（同用户覆盖，幂等）。 */
    @Transactional
    public void setQuietHours(long userId, QuietHours hours) {
        NotificationPreference preference = repository.findById(userId)
                .orElseGet(() -> new NotificationPreference(userId, clock.instant()));
        preference.setQuietHours(hours, clock.instant());
        repository.save(preference);
    }

    /** 清除免打扰时段。@return false = 该用户本就没有偏好行 */
    @Transactional
    public boolean clearQuietHours(long userId) {
        return repository.findById(userId)
                .map(preference -> {
                    preference.setQuietHours(null, clock.instant());
                    repository.save(preference);
                    return true;
                })
                .orElse(false);
    }

    /** 该用户已配置的时段；未配置返回 {@code null}。 */
    public QuietHours quietHoursOf(long userId) {
        return repository.findById(userId).map(NotificationPreference::quietHours).orElse(null);
    }

    /** 该用户在该时刻是否处于免打扰时段（未配置 → false）。 */
    public boolean isQuietAt(long userId, LocalTime moment) {
        QuietHours hours = quietHoursOf(userId);
        return hours != null && hours.covers(moment);
    }
}

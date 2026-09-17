package com.tg.heyisheng.bot.core.notify;

import org.springframework.data.jpa.repository.JpaRepository;

/** 用户通知偏好仓库（模块十 §11.1）。 */
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {
}

package com.tg.heyisheng.bot.core.notify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 延迟通知仓库（模块十 §11.1）。 */
public interface DeferredNotificationRepository extends JpaRepository<DeferredNotification, Long> {

    /** 某用户的待发通知（按入队顺序）——冲刷前判断其是否仍在免打扰时段。 */
    List<DeferredNotification> findByUserIdOrderByIdAsc(long userId);

    /** 全部待发通知（调度器遍历用）。 */
    List<DeferredNotification> findAllByOrderByIdAsc();
}

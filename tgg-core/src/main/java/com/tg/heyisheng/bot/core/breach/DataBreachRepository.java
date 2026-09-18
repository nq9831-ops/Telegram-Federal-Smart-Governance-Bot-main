package com.tg.heyisheng.bot.core.breach;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 数据泄露事件仓库（模块十 §11.2）。
 *
 * <p>与 {@code AuditLogRepository} 同款：<b>不继承 {@code JpaRepository}</b>，
 * 只声明需要的方法——通报记录是合规证据，删除入口不该存在（同审计表的取舍）。
 */
public interface DataBreachRepository extends Repository<DataBreachIncident, Long> {

    DataBreachIncident save(DataBreachIncident incident);

    Optional<DataBreachIncident> findById(Long id);

    /** 全部事件（倒序 = 最新在前）。 */
    List<DataBreachIncident> findAllByOrderByIdDesc();

    /** 尚未通报的事件（催办任务的输入）。 */
    List<DataBreachIncident> findByReportedAtIsNullOrderByIdAsc();
}

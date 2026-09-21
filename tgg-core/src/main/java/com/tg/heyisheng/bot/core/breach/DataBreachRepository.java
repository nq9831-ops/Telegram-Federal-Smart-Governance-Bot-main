package com.tg.heyisheng.bot.core.breach;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /**
     * 原子标记「已通报」：仅当 {@code reported_at} 仍为空时写入，返回受影响行数。
     *
     * <p><b>为什么不是「findById → 内存判定 → save」</b>：那是读-改-写，两事务并发时会都读到
     * 空的 {@code reported_at}、都判定「我是首个」、后写者覆盖先写者的首报时间——而首报时间是合规证据。
     * 把判定下推到 {@code WHERE ... IS NULL}，以受影响行数作<b>唯一闸门</b>：并发时只有一个事务得 1，
     * 其余得 0（数据库的行级锁保证）。
     *
     * @return 1 = 本次写入成功；0 = 不存在或已通报（幂等）
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DataBreachIncident i SET i.reportedAt = :at, i.reportedBy = :operator "
            + "WHERE i.id = :id AND i.reportedAt IS NULL")
    int markReportedIfUnreported(@Param("id") long id, @Param("at") Instant at,
                                 @Param("operator") Long operator);
}

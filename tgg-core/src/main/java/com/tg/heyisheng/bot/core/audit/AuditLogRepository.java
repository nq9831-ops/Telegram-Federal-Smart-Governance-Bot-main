package com.tg.heyisheng.bot.core.audit;

import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 审计日志仓库（模块十 §11.2）——<b>只追加</b>。
 *
 * <p><b>刻意继承 {@link Repository}（空标记接口）而不是 {@code JpaRepository}</b>：
 * 后者自带 {@code delete / deleteById / deleteAll / deleteAllInBatch}，等于给「删审计」备好了入口。
 * 只声明需要的方法后，<b>删除在编译期就不可达</b>——比在注释里写「请勿删除」强得多
 * （本项目反复吃过「接口留了口子，实现/调用方就真去用」的亏）。
 *
 * <p>⚠️ <b>这仍不是全部</b>：它挡得住本项目代码，挡不住运维直接执行 SQL。
 * 数据层的硬保证（触发器或回收 DELETE/UPDATE 权限）属部署侧，且触发器在受限 MySQL 上建不了
 * （缺 SUPER / binlog 开启时会报 ERROR 1419），故不写进迁移。
 */
public interface AuditLogRepository extends Repository<AuditEntry, Long> {

    /** 追加一条审计。 */
    AuditEntry save(AuditEntry entry);

    /** 按 id 查（验收与排错用）。 */
    Optional<AuditEntry> findById(Long id);

    /** 最近若干条（倒序 = 最新在前）。 */
    List<AuditEntry> findTop100ByOrderByIdDesc();

    /** 某操作者的审计轨迹（倒序）。 */
    List<AuditEntry> findByActorIdOrderByIdDesc(long actorId);

    /** 某时间之后的全部条目（数据导出用）。 */
    List<AuditEntry> findByOccurredAtAfterOrderByIdAsc(Instant since);
}

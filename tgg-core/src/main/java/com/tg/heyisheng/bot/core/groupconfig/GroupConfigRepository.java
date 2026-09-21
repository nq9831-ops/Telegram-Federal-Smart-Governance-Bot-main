package com.tg.heyisheng.bot.core.groupconfig;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * 群组配置仓库。
 *
 * <p>本阶段只需要按主键查/存；复杂查询等业务需要时再加。
 *
 * <p><b>为什么开关翻转走原生 upsert</b>：「先 findById 判空、再 save」是 check-then-insert——
 * 两个请求并发地对同一个<b>尚未登记</b>的群做首次写入时，后到者会撞主键约束抛
 * {@code DataIntegrityViolationException}。MySQL 的 {@code INSERT ... ON DUPLICATE KEY UPDATE}
 * 把「不存在则插入、存在则更新」压成<b>一条原子语句</b>，从根上消除该竞态。
 * （同款范式：{@code BannedWordService} 与 {@code CreditScoreRepository} 的 {@code INSERT IGNORE}。）
 */
public interface GroupConfigRepository extends JpaRepository<GroupConfig, Long> {

    /**
     * 翻转某群的开关（幂等）：不存在则以给定开关新建，存在则只更新 {@code enabled} 与 {@code updated_at}。
     *
     * <p>不写 {@code title}（存在时保持原标题不变；新建时为 NULL，与旧行为一致）。
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT INTO group_configs (chat_id, title, enabled, created_at, updated_at) "
            + "VALUES (:chatId, NULL, :enabled, :now, :now) "
            + "ON DUPLICATE KEY UPDATE enabled = :enabled, updated_at = :now",
            nativeQuery = true)
    void upsertEnabled(@Param("chatId") Long chatId, @Param("enabled") boolean enabled,
                       @Param("now") Instant now);

    /**
     * 登记/更新某群的标题（幂等）：不存在则新建（默认启用），存在则只更新 {@code title} 与 {@code updated_at}。
     *
     * <p>与 {@link #upsertEnabled} 同款原生 upsert——取代「先 findById 判空再 save」的并发主键冲突。
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT INTO group_configs (chat_id, title, enabled, created_at, updated_at) "
            + "VALUES (:chatId, :title, TRUE, :now, :now) "
            + "ON DUPLICATE KEY UPDATE title = :title, updated_at = :now",
            nativeQuery = true)
    void upsertOnRegister(@Param("chatId") Long chatId, @Param("title") String title,
                          @Param("now") Instant now);
}

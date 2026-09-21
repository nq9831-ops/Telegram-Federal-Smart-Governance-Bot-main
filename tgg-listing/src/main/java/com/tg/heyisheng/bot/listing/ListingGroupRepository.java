package com.tg.heyisheng.bot.listing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * 群组收录库仓库。
 *
 * <p>写入幂等由数据库承担：{@code chat_id} 唯一键（V6 的 {@code uk_listing_groups_chat}）
 * + {@code INSERT IGNORE}——不用「先查再存 + catch 唯一约束异常」，
 * 因为 Hibernate 在 flush 失败后 session 即不可用，同事务后续操作全崩
 * （沿用 {@code CreditScoreRepository.insertIfAbsent} 的既有模式与坑 21 纪律）。
 */
public interface ListingGroupRepository extends JpaRepository<ListingGroup, Long> {

    /**
     * 首次收录时建行，命中唯一约束静默跳过（重复提交不覆盖既有条目）。
     *
     * @return 受影响行数：1 = 新建；0 = 该 {@code chat_id} 已存在（被忽略）
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT IGNORE INTO listing_groups "
            + "(chat_id, invite_link, title, submitter_user_id, status, fail_count, created_at, updated_at) "
            + "VALUES (:chatId, :inviteLink, :title, :submitterUserId, :status, 0, :now, :now)",
            nativeQuery = true)
    int insertIfAbsent(@Param("chatId") long chatId,
                       @Param("inviteLink") String inviteLink,
                       @Param("title") String title,
                       @Param("submitterUserId") Long submitterUserId,
                       @Param("status") String status,
                       @Param("now") Instant now);

    /**
     * 按状态取批（验证任务只扫 {@code ACTIVE}；审计/申诉侧可按 {@code SUSPENDED} 取软删记录）。
     *
     * <p>按主键升序保证扫描顺序稳定、可分段续跑。
     */
    List<ListingGroup> findByStatusOrderByIdAsc(String status);

    /**
     * 某<b>作者</b>提交的收录（倒序）——<b>数据范围 OWN 的查询入口</b>。
     *
     * <p>过滤写在<b>查询条件</b>里（而非「查全部再在控制器里过滤」）：后者一旦漏过滤，
     * 就会把别人的行原样发出去——而这正是本项目列为「必须靠查询条件守」的不变量。
     */
    List<ListingGroup> findBySubmitterUserIdOrderByIdDesc(Long submitterUserId);
}

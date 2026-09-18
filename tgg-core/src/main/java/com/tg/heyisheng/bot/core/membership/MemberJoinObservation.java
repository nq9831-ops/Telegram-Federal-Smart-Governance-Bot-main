package com.tg.heyisheng.bot.core.membership;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 成员入群时间观察（模块九 §10.3「入群时长 ≥30 天」门槛的<b>唯一</b>数据源）——按「用户 × 群」一行。
 *
 * <p><b>为什么是被动采集</b>：Telegram Bot API 没有「查询某成员何时入群」的接口，
 * 只有 {@code chat_member} 更新会携带状态变更。故本表记的是
 * 「**bot 观察到的**入群时间」，不是权威入群时间——bot 成为管理员之前就已入群的成员不会有行。
 *
 * <p><b>数据最小化</b>：只有四个字段，没有用户名/昵称/邀请人等任何衍生个人数据；
 * 且成员退群时其行被**删除**（表内始终只含当前在群成员），另由保留策略兜底清理。
 *
 * <p><b>无 setter</b>：写入只经仓库的 {@code INSERT IGNORE}（保持最早观察到的入群时间），
 * 不存在「改一行」的业务动作——故本类是只读实体，避免调用方误改 {@code joinedAt}。
 */
@Entity
@Table(name = "member_join_observations")
public class MemberJoinObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 最近一次**观察到**的入群时间（UTC）；已有行不覆盖。 */
    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    /** 最后一次写入/更新时间（UTC），供保留策略与排障。 */
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    /** JPA 要求的无参构造器（protected，避免业务代码误用）。 */
    protected MemberJoinObservation() {
    }

    public MemberJoinObservation(Long chatId, Long userId, Instant joinedAt, Instant observedAt) {
        this.chatId = chatId;
        this.userId = userId;
        this.joinedAt = joinedAt;
        this.observedAt = observedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getChatId() {
        return chatId;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getObservedAt() {
        return observedAt;
    }
}

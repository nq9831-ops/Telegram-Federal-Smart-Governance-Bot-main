package com.tg.heyisheng.bot.core.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 敏感话题违规计数（模块九 §10.5 递进处置的判据）——<b>按「用户 × 群」</b>一行。
 *
 * <p><b>为什么需要它</b>：V5.0 原文的处置是**按该用户的累计次数递进**
 * （首次删除+警告 / 二次禁言 24h / 三次联邦标记），而不是按话题的严重度。
 * 没有计数器就无法区分「初犯」与「屡犯」。
 *
 * <p><b>只存计数与时间，不存任何消息内容</b>——与全项目隐私口径一致。
 * 表结构由 Flyway 管理（V11），JPA 侧 {@code ddl-auto: validate}。
 */
@Entity
@Table(name = "sensitive_topic_strikes")
public class SensitiveTopicStrike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "strike_count", nullable = false)
    private int strikeCount;

    @Column(name = "last_at", nullable = false)
    private Instant lastAt;

    /** JPA 要求的无参构造器（protected，避免业务代码误用）。 */
    protected SensitiveTopicStrike() {
    }

    public SensitiveTopicStrike(Long chatId, Long userId, Instant lastAt) {
        this.chatId = chatId;
        this.userId = userId;
        this.strikeCount = 1;
        this.lastAt = lastAt;
    }

    /** 再记一次违规，返回累计次数。 */
    public int increment(Instant at) {
        this.strikeCount += 1;
        this.lastAt = at;
        return this.strikeCount;
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

    public int getStrikeCount() {
        return strikeCount;
    }

    public Instant getLastAt() {
        return lastAt;
    }
}

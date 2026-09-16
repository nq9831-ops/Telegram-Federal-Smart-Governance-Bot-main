package com.tg.heyisheng.bot.core.wordfilter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 一条按群违禁词。
 *
 * <p>表结构由 Flyway 管理（{@code V3__init_banned_words.sql}），JPA 侧
 * {@code ddl-auto: validate}——Hibernate 只校验、不改表。
 *
 * <p><b>词是配置而非对话内容</b>：它由管理员主动提交、需要长期保存；
 * 与「消息原文零存储」约束不冲突（后者管的是被监听的聊天内容）。
 */
@Entity
@Table(name = "banned_words")
public class BannedWord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 群组 ID。Telegram 群 ID 为负数且可超出 INT 范围。 */
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    /** 违禁词原文（匹配时大小写不敏感）。 */
    @Column(name = "word", nullable = false)
    private String word;

    /** 添加者用户 ID。 */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA 要求的无参构造器（protected，避免业务代码误用）。 */
    protected BannedWord() {
    }

    public BannedWord(Long chatId, String word, Long createdBy) {
        this.chatId = chatId;
        this.word = word;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getChatId() {
        return chatId;
    }

    public String getWord() {
        return word;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

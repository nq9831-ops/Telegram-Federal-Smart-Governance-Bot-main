-- 按群违禁词表（模块三 · 群组管理 —— 违禁词切片）。
--
-- 词是群主的自治配置（V5.0：群主管群），命中即拦截（删除）该消息。
-- 词本身是管理员**主动提交**的管理数据，不是被监听的对话内容——
-- 与「消息原文零存储」约束不冲突：前者是配置，后者是用户聊天内容。
--
-- 唯一约束 (chat_id, word)：同群同词只存一条，重复添加幂等。
-- 不做大小写敏感：匹配侧统一按大小写不敏感处理（词按原文存储，便于展示）。
CREATE TABLE IF NOT EXISTS banned_words
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    chat_id    BIGINT       NOT NULL COMMENT '群组 ID（Telegram 群 ID 为负数）',
    word       VARCHAR(255) NOT NULL COMMENT '违禁词原文',
    created_by BIGINT       NULL COMMENT '添加者用户 ID',
    created_at DATETIME(6)  NOT NULL COMMENT '添加时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_banned_words_chat_word (chat_id, word)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='按群违禁词表';

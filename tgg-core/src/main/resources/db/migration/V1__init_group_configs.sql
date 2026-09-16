-- 群组级配置：群组管理类功能的最小底表。
--
-- 设计取舍：刻意只建这一张表。词库、管理员名单、信用分等表的字段取决于尚未设计的模块，
-- 先建会导致返工或留下空壳表。
--
-- chat_id 用 BIGINT：Telegram 的群/超级群 ID 是负数，且可超出 INT 范围。
CREATE TABLE IF NOT EXISTS group_configs
(
    chat_id    BIGINT       NOT NULL COMMENT '群组 ID（Telegram 群 ID 为负数）',
    title      VARCHAR(255) NULL COMMENT '群标题，便于后台辨识',
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '功能总开关',
    created_at DATETIME(6)  NOT NULL COMMENT '创建时间',
    updated_at DATETIME(6)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (chat_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='群组级配置';

-- 模块九 §10.5：群组「话题标签」——用于敏感话题分级的**豁免**。
--
-- 为什么是**新表**而不是 group_configs 加一列（用户 2026-09-17 拍板）：
--   ① 一对多——一个群可声明多个话题标签，单列塞逗号串无法索引、无法单独增删；
--   ② 可审计——每行带 created_by / created_at，谁在何时加了什么标签可追；
--   ③ 可单独增删——撤销一个标签就是删一行，不必重写整串。
--
-- 约束：同群同标签唯一（重复添加幂等）。
CREATE TABLE IF NOT EXISTS group_topic_tags
(
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    chat_id    BIGINT      NOT NULL COMMENT '群组 ID（Telegram 群 ID 为负数）',
    tag        VARCHAR(32) NOT NULL COMMENT '话题标签（小写：gambling/adult/politics…）',
    created_by BIGINT      NULL COMMENT '添加人 userId',
    created_at DATETIME(6) NOT NULL COMMENT '添加时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_topic_tag (chat_id, tag),
    INDEX idx_group_topic_chat (chat_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='群组话题标签（敏感话题分级的豁免依据）';

-- 模块九 §10.5 递进处置 + 存储清理（2026-09-17）。
--
-- ① 敏感话题违规计数（V5.0 原文的处置是**按用户累计次数递进**，
--    首次删除+警告+扣5 / 二次禁言24h+扣15 / 三次联邦标记+扣30——而**不是**按话题等级）。
--    故需要一个「用户 × 群」的计数器作为判据。一条一个用户一行（唯一键），累加即更新。
CREATE TABLE IF NOT EXISTS sensitive_topic_strikes
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    chat_id      BIGINT      NOT NULL COMMENT '群组 ID（Telegram 群 ID 为负数）',
    user_id      BIGINT      NOT NULL COMMENT '发布者 ID',
    strike_count INT         NOT NULL DEFAULT 0 COMMENT '该用户在本群的累计敏感话题违规次数',
    last_at      DATETIME(6) NOT NULL COMMENT '最近一次违规时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_strike_chat_user (chat_id, user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='敏感话题违规计数（按用户×群，§10.5 递进处置依据）';

-- ② 去掉 group_topic_tags 上的冗余索引。
--    uk_group_topic_tag(chat_id, tag) 的最左前缀已覆盖 idx_group_topic_chat(chat_id)，
--    后者在 InnoDB 下只多一棵 B+ 树、拖慢写入（`051c992` 提交后审查发现 ④，用户 2026-09-17 批准删除）。
DROP INDEX idx_group_topic_chat ON group_topic_tags;

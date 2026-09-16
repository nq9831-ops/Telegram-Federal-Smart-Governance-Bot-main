-- 中高风险审核命中的待人工复核队列（模块九，下一步第 2 项）。
--
-- 隐私约束：本表**刻意不存消息正文**——V5.0 要求「违规片段脱敏存储」，
-- 且项目定位隐私优先（消息原文零存储）。存的是「判定结论」+ 定位信息（群/用户/消息 id），
-- 人工复核时凭这些信息到 Telegram 端核实。
--
-- chat_id / user_id 存**明文**：队列的用途是「人工复核 → 定位并处置」，
-- 哈希后无法定位到具体用户，队列即失去可操作性。
-- 日志侧仍走 IdHasher 哈希（两者用途不同：日志是审计脱敏，DB 是业务定位）。
--
-- status 用字符串存储（PENDING/APPROVED/REJECTED），不用 TINYINT：
-- 可读性优先，且后台查询/人工排查时无需再查码表。
CREATE TABLE IF NOT EXISTS moderation_review_queue
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    chat_id    BIGINT       NOT NULL COMMENT '群组 ID（Telegram 群 ID 为负数）',
    user_id    BIGINT       NULL COMMENT '发布者 ID（频道帖等可能缺失）',
    message_id INT          NULL COMMENT '触发消息 ID',
    rule_ids   VARCHAR(255) NOT NULL COMMENT '命中的规则 id（逗号分隔）',
    risk_level VARCHAR(16)  NOT NULL COMMENT '风险等级：LOW / MEDIUM / HIGH',
    status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '复核状态：PENDING / APPROVED / REJECTED',
    created_at DATETIME(6)  NOT NULL COMMENT '创建时间',
    updated_at DATETIME(6)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_status_created (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='待人工复核的审核命中队列';

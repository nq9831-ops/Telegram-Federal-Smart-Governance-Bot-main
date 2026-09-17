-- 模块八 · 联邦治理：接收的处罚令 + 申诉队列。
--
-- federation_penalties：他节点广播来的处罚令。order_id 为幂等键——重复投递被忽略（不重复执行封禁）。
-- 主体 id 存【明文】（与 credit_scores 同口径：执行跨群封禁需定位主体）。表内不含消息正文。
CREATE TABLE IF NOT EXISTS federation_penalties
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    order_id     VARCHAR(64)  NOT NULL COMMENT '处罚令 UUID（幂等键）',
    subject_type VARCHAR(16)  NOT NULL COMMENT '主体类型：INDIVIDUAL / GROUP / MERCHANT',
    subject_id   BIGINT       NOT NULL COMMENT '被处罚主体 ID',
    penalty_type VARCHAR(32)  NOT NULL COMMENT 'WARN / MUTE / REMOVE / REPORT_TO_FEDERATION',
    issued_at    DATETIME(6)  NOT NULL COMMENT '令签发时间',
    signature    VARCHAR(256) NULL COMMENT 'Ed25519 签名（Base64；64 字节签名的 Base64 为 88 字符）',
    origin_node  VARCHAR(512) NULL COMMENT '来源节点基址',
    received_at  DATETIME(6)  NOT NULL COMMENT '接收时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_federation_penalties_order (order_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='接收的联邦处罚令';

-- federation_appeals：申诉队列（用户提交 → 待审 → 联邦管理员裁定）。
-- appeal_text 是用户【主动提交】的申诉正文，不是被监听的对话内容——与「消息原文零存储」不冲突。
CREATE TABLE IF NOT EXISTS federation_appeals
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    user_id     BIGINT      NOT NULL COMMENT '申诉人 user id',
    appeal_type VARCHAR(32) NOT NULL COMMENT 'FEDBAN_UNBAN / FED_ADMIN',
    appeal_text TEXT        NULL COMMENT '申诉正文（用户主动提交）',
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
    created_at  DATETIME(6) NOT NULL COMMENT '提交时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='联邦申诉队列';

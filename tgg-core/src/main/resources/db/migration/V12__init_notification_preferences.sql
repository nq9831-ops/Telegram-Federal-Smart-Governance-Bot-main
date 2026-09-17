-- 模块十 §11.1「免打扰时段」：用户偏好 + 延迟队列（2026-09-18）。
--
-- ① 偏好：每个用户一行；未配置 = 不设免打扰（沉默即默认放行，不擅自替用户消音）。
--    时段用 TIME 存（仅时刻，不含日期）——「22:00-08:00」这类跨午夜区间由应用层判定。
CREATE TABLE IF NOT EXISTS notification_preferences
(
    user_id     BIGINT      NOT NULL COMMENT '用户 ID（一用户一行）',
    quiet_start TIME        NULL COMMENT '免打扰起始（含）',
    quiet_end   TIME        NULL COMMENT '免打扰结束（不含）',
    updated_at  DATETIME(6) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='用户通知偏好（免打扰时段）';

-- ② 延迟队列：处于免打扰时段时的**非紧急**通知在此暂存，时段结束后由调度器冲刷。
--
--    为什么落库而不是放内存：内存队列在重启时**静默丢失**——用户永远不知道自己错过了什么，
--    而本项目对「静默丢弃」的态度是明确的（降级必须可见）。落库后重启不丢，冲刷前可审计。
CREATE TABLE IF NOT EXISTS notification_deferred
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    user_id    BIGINT       NOT NULL COMMENT '收件人 ID',
    level      VARCHAR(16)  NOT NULL COMMENT '通知级别（URGENT 不入队）',
    text       VARCHAR(1000) NOT NULL COMMENT '通知正文（由生产侧拼装，不含用户消息原文）',
    created_at DATETIME(6)  NOT NULL COMMENT '入队时间',
    PRIMARY KEY (id),
    INDEX idx_deferred_user (user_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='免打扰时段内延迟发送的通知';

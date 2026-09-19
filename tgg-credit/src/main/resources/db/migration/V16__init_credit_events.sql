-- 信用分流水（模块七 · §3.3 信用分流水与幂等）。
--
-- 为什么需要它：credit_scores 只存【当前分值】，一条 UPDATE 覆盖即抹掉「为什么是这个分数」——
-- 无法解释、无法追溯、无法回滚（复核推翻想撤销扣分时，账本里已无痕迹）。
-- 本表把每次变动记成【不可变事件】，与 credit_scores 组成「流水 + 快照」。
--
-- 幂等：idempotency_key 上的唯一约束是去重的**唯一依据**。键必须由生产侧提供【业务稳定标识】
-- （如 moderation:<chatId>:<messageId>），使 Telegram 重投同一条 update 只扣一次分。
-- ⚠️ 不可用 CreditEvent.eventId —— 它是每次生成的 UUID，重投必然是新值，注定去不了重。
--
-- subject_id 存【明文】（与 credit_scores 同口径：施加处罚需定位主体，哈希后无法定位）。
-- 表内不含消息正文。
CREATE TABLE IF NOT EXISTS credit_events
(
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    subject_type    VARCHAR(16)  NOT NULL COMMENT '主体类型：INDIVIDUAL / GROUP / MERCHANT',
    subject_id      BIGINT       NOT NULL COMMENT '主体 ID（个人 userId / 群 chatId / 商家 id）',
    event_type      VARCHAR(32)  NOT NULL COMMENT '事件类型（CreditEventType 名）',
    severity        VARCHAR(16)  NULL COMMENT '风险等级（多条命中取最高）',
    hard_line       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否硬红线',
    score_before    INT          NOT NULL COMMENT '本次变动前分值',
    score_delta     INT          NOT NULL COMMENT '分值增量（负数=扣分）',
    score_after     INT          NOT NULL COMMENT '本次变动后分值（夹取后）',
    source          VARCHAR(32)  NULL COMMENT '来源模块标识',
    idempotency_key VARCHAR(128) NULL COMMENT '业务幂等键；同键只记一次（NULL 不参与去重）',
    occurred_at     DATETIME(6)  NOT NULL COMMENT '事件发生时间',
    recorded_at     DATETIME(6)  NOT NULL COMMENT '记账时间',
    PRIMARY KEY (id),
    -- MySQL 语义：NULL 之间互不相等，故「无幂等键」的事件可多行共存，不会被误去重
    UNIQUE KEY uk_credit_events_idempotency (idempotency_key),
    INDEX idx_credit_events_subject (subject_type, subject_id, recorded_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='信用分流水（不可变事件）';

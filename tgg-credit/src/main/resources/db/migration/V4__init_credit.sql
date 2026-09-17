-- 信用分账本（模块七 · 信用分体系）。
--
-- 三套分（个人 / 群组 / 商家）共用此表，按 subject_type 区分——三张结构相同的表是多余的。
--
-- 主体 id 存【明文】：信用分需能定位主体以施加处罚（哈希后无法定位），
-- 故与 moderation_review_queue 同口径。日志侧仍用 IdHasher 脱敏。
--
-- 唯一约束 (subject_type, subject_id)：同一主体只有一行账本，重复记账走 UPDATE 而非新增。
CREATE TABLE IF NOT EXISTS credit_scores
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    subject_type VARCHAR(16) NOT NULL COMMENT '主体类型：INDIVIDUAL / GROUP / MERCHANT',
    subject_id   BIGINT      NOT NULL COMMENT '主体 ID（个人 userId / 群 chatId / 商家 id）',
    score        INT         NOT NULL COMMENT '当前信用分',
    updated_at   DATETIME(6) NOT NULL COMMENT '最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_credit_scores_subject (subject_type, subject_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='信用分账本（三套分共用）';

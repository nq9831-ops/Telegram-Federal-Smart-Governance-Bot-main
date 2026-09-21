-- 审核案件的**当事人申诉**（模块九 §10.4 的申诉侧；对应原文 §2.3 的申诉状态机）。
--
-- 为什么单独建表而不并入 moderation_review_queue：
--   ① 申诉是**独立实体**（有自己的状态、理由、处理人），与案件本身是 1:N（同一案件可能被
--      当事人申诉、也可能被运营者补充说明）；
--   ② 案件队列是**审核产物**，申诉是**当事人输入**——前者由系统写，后者由用户写，混表会让
--      「谁能写哪几列」这条边界消失。
--
-- ⚠️ 安全边界：案件号在群内是**公开的**（删除告知里带着编号，见 ModerationMessages.deletedNoticeWithCase），
--    所以**任何人都知道编号**。故本表的使用方必须校验「user_id 正是该案件的当事人」——
--    编号可公开，申诉权不可公开。
--
-- 隐私：reason 是用户**主动提交**的申诉正文（不是被监听的对话内容），与 federation_appeals.appeal_text
--       同一口径；它落库、但**不进日志、不进审核判定**。
CREATE TABLE IF NOT EXISTS moderation_case_appeals
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    review_id  BIGINT       NOT NULL COMMENT '被申诉的审核案件号（moderation_review_queue.id）',
    user_id    BIGINT       NOT NULL COMMENT '申诉人（须为该案件的当事人）',
    reason     VARCHAR(500) NOT NULL COMMENT '申诉理由（用户主动提交；不进日志、不进审核判定）',
    status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '申诉状态：当前仅 PENDING（提交即待处理）',
    created_at DATETIME(6)  NOT NULL COMMENT '提交时间（UTC）',
    PRIMARY KEY (id),
    -- 一人一案只允许一条：防止同一当事人重复提交把队列灌满（幂等的落点）。
    UNIQUE KEY uk_case_appeal_review_user (review_id, user_id),
    INDEX idx_case_appeal_status_created (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='审核案件的当事人申诉';

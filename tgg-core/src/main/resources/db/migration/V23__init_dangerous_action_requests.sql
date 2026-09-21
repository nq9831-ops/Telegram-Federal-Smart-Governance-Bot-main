-- 危险动作双人复核（模块十一 · 护栏；原文 §12.2「不可逆对外动作需两人」）。
--
-- 为什么只覆盖「触发重启」：它是本系统唯一的**不可逆对外动作**（杀进程，能否再起来取决于外部监管）。
-- 而 §12.2 原文点名的「大额扣分」**不做双人审批**——扣分不产生任何外部动作（tgg-credit 对
-- Ban/Restrict 零调用），且挂起会让硬红线的「立即处置」失效。理由见 ARCHITECTURE §7.3。
--
-- ⚠️ 为什么需要这张表而不是「前端弹两次确认」：双人复核的实质是**两个不同主体**各表达一次意愿，
--    必须落在服务端、可审计、且能校验「发起人 ≠ 批准人」。前端确认框只是体验，不构成复核。
--
-- ⚠️ 超管**不走本表**：超管可直接重启（他是最高权威）。若超管发起也要等批准，而系统只引导
--    单个超管，就会死锁。故「双人」约束的是**低权限主体的单方面不可逆动作**。
CREATE TABLE IF NOT EXISTS dangerous_action_requests
(
    id                 BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    action_type        VARCHAR(32) NOT NULL COMMENT '动作类型：RESTART',
    requested_by_type  VARCHAR(16) NOT NULL COMMENT '发起人主体类型（ADMIN_ACCOUNT）',
    requested_by_id    BIGINT      NOT NULL COMMENT '发起人主体 id',
    status             VARCHAR(16) NOT NULL COMMENT 'PENDING / APPROVED / REJECTED',
    decided_by_type    VARCHAR(16) NULL COMMENT '批准/拒绝人主体类型',
    decided_by_id      BIGINT      NULL COMMENT '批准/拒绝人主体 id',
    created_at         DATETIME(6) NOT NULL COMMENT '发起时间',
    decided_at         DATETIME(6) NULL COMMENT '裁决时间',
    note               VARCHAR(255) NULL COMMENT '说明（不含敏感内容）',
    PRIMARY KEY (id),
    INDEX idx_dangerous_action_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='危险动作双人复核请求';

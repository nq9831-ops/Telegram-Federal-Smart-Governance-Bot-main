-- 审计日志加「案件号」——让「发生了什么」可**按案件**聚合（模块九/十；对应原文 §4.3 的案件时间线）。
--
-- 为什么需要它（实测的理由，不是推测）：
--   ① 现有 target 的语义是「chatId / 被判主体 id」（见 V13 注释），**不唯一**——同一 target
--      可能是一条命令的群、也可能是一次审核的被判主体，无法当案件号用；
--   ② target 上**没有索引**（V13 只有 actor_time 与 time 两个索引），按它聚合会全表扫描；
--   ③ 更重要的是：审核路径此前**根本不写审计**（AuditService 的调用点只有审计切面与 tgg-admin），
--      所以「案件发生过什么」在数据上是一片空白——本列与写入侧是配套的，缺任一边都无意义。
--
-- 语义：case_id = moderation_review_queue.id，即告知里给出的「案件 #N」。可为 NULL：
--   命令路径与系统级动作没有案件号，强行填 0 会让「无案件」与「案件 0」混淆。
--
-- ⚠️ 本迁移只加列与索引，不回填历史行（历史审核动作本就没有记录，回填无从谈起）。
ALTER TABLE audit_log
    ADD COLUMN case_id BIGINT NULL COMMENT '关联案件号（moderation_review_queue.id）；无关动作为 NULL' AFTER target;

-- 按案件聚合时间线用（后台案件详情页）。放在 occurred_at 前作前缀列，与 idx_audit_actor_time 同思路。
CREATE INDEX idx_audit_case_time ON audit_log (case_id, occurred_at);

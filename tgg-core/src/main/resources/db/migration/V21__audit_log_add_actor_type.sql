-- 审计日志加「主体类型」——让 actor_id 从「一个裸 userId」变成「(类型, id) 二元组」。
--
-- 为什么必须与账号表**同波**引入（不能延后）：
--   V13 的 actor_id 注释是「操作者 userId」，语义里**没有类型**；而 ExportMyDataCommandHandler
--   按 actor_id 单条件导出「关于我自己的数据」。一旦引入后台账号，超管/操作员的账号 id 与
--   TG userId 落在**同一个 BIGINT 数值空间**里——id=42 的账号与 userId=42 的 TG 用户将互相
--   读到对方的审计记录。这是**前瞻性缺陷**（当前账号表还不存在，故尚未成立），
--   但账号表一落地即成立，因此本列必须与 V20 同波，不得延后。
--
-- 语义：TG_USER = Telegram 用户（命令路径、审核路径的被处置者）；
--       ADMIN_ACCOUNT = 后台账号（超管 / 操作员）。
--
-- ⚠️ DEFAULT 'TG_USER'：本列非空，历史行（迁移前写入的全部是 TG 用户动作）需要合法默认值。
--    迁移后应用侧所有写入点都会显式带类型（见 AuditService），默认值只为兜历史行。
ALTER TABLE audit_log
    ADD COLUMN actor_type VARCHAR(16) NOT NULL DEFAULT 'TG_USER'
        COMMENT '主体类型：TG_USER / ADMIN_ACCOUNT' AFTER actor_id;

-- 双条件导出用：按 (actor_type, actor_id) 取某主体自己的轨迹，occurred_at 作排序前缀列。
CREATE INDEX idx_audit_actor_type_time ON audit_log (actor_type, actor_id, occurred_at);

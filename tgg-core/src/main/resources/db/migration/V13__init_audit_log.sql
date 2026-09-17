-- 模块十 §11.2「全链路审计」（2026-09-18）。
--
-- 只追加、不修改、不删除。**本迁移只建表，不建触发器**，原因见下（实测）。
--
-- ⚠️ 为什么没有用触发器实现「不可删」：
--    本机实测 CREATE TRIGGER 直接失败——
--      ERROR 1419 (HY000): You do not have the SUPER privilege and binary logging is enabled
--    MySQL 在开启 binlog 时要求 SUPER 权限（或 log_bin_trust_function_creators=1）才能建触发器。
--    托管数据库（RDS / 云 MySQL）普遍禁用触发器或需要额外开关——把它写进迁移＝这些环境**永远起不来**。
--    故数据层的硬保证交给部署侧（见 docs/DEPLOYMENT-VERIFICATION.md），
--    应用层则由「仓库接口不暴露任何删除方法」保证（AuditLogRepository）。
--
-- 隐私：detail 只放「动作 + 结论」，**绝不放用户消息正文**（与全项目口径一致）。
CREATE TABLE IF NOT EXISTS audit_log
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    actor_id    BIGINT       NULL COMMENT '操作者 userId（系统动作为 NULL）',
    action      VARCHAR(128) NOT NULL COMMENT '动作标识（真实命令处理器类名）',
    target      BIGINT       NULL COMMENT '作用对象（如 chatId / 被判主体 id）',
    outcome     VARCHAR(16)  NOT NULL COMMENT '结果：SUCCESS / FAILURE',
    detail      VARCHAR(512) NULL COMMENT '补充说明（不含消息正文）',
    occurred_at DATETIME(6)  NOT NULL COMMENT '发生时间',
    PRIMARY KEY (id),
    INDEX idx_audit_actor_time (actor_id, occurred_at),
    INDEX idx_audit_time (occurred_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='全链路审计日志（只追加；数据层加固见部署清单）';

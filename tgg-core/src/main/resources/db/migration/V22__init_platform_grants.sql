-- 平台能力授权（模块十一 · 权限模型）——超管给操作员 / TG 用户授予平台能力点。
--
-- 为什么需要：用户要求「可以增加建多个『操作员账号』功能 由超级管理员来分配权限」。
-- 在此之前，平台层是**四套独立白名单**（tgg.moderation.reviewers / tgg.admin.config-admins /
-- tgg.federation.admins / tgg.merchant.reviewers），各异构、且只能给 TG userId。本表把它们
-- 收敛成一处**可被超管增删**的授权账本；账本为空时各 guard 回落到原有配置键（升级不破坏线上授权）。
--
-- ⚠️ subject_type 不可省：后台账号 id 与 TG userId 落在同一 BIGINT 数值空间，
--    只按 subject_id 授权会让 id 撞车的两个主体共享能力（与 audit_log.actor_type 同一条理由）。
--
-- ⚠️ 超管**不在**本表：超管天然全有（见 PlatformPermission 注释）；若也把超管写进来，
--    一旦误删某行就会出现「超管失去某能力」的诡异状态。
--
-- GRANT_MANAGE 也不该出现在本表——它是「分配权限」的权限，只属超管；写进来等于开出提权路。
CREATE TABLE IF NOT EXISTS platform_grants
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    subject_type VARCHAR(16) NOT NULL COMMENT '主体类型：ADMIN_ACCOUNT / TG_USER',
    subject_id   BIGINT      NOT NULL COMMENT '主体 id（账号 id 或 TG userId）',
    permission   VARCHAR(32) NOT NULL COMMENT '能力点（PlatformPermission 名）',
    granted_at   DATETIME(6) NOT NULL COMMENT '授予时间',
    granted_by   BIGINT      NULL COMMENT '授予者账号 id（超管）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_grant (subject_type, subject_id, permission),
    INDEX idx_platform_grant_subject (subject_type, subject_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='平台能力授权账本（超管唯一写者）';

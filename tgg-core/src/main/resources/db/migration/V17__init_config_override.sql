-- 配置中心（模块十一 扩展）：运行期配置覆盖表。
--
-- 用途：Web 后台「配置中心」写入的**非密钥**配置覆盖值。解析顺序为
--   本表（override） → 环境变量 / application.yml 默认。
--
-- ⚠️ 只存非密钥键：密钥/引导态键（TGG_WEBHOOK_SECRET、TGG_BOT_TOKEN、TGG_DB_PASSWORD 等）
--    由应用层 ConfigCatalog 分类拦截，永不落此表（写进库＝密钥进 DB + 浏览器 + 审计）。
--
-- ⚠️ 本表在**启动期**被 ConfigOverrideEnvironmentPostProcessor 读取（用于装配开关「重启生效」），
--    故它必须在 Flyway 迁移完成后才存在——首次启动该表尚不存在时，注入器静默跳过。
--
-- 风格对齐 V13__init_audit_log.sql（InnoDB / utf8mb4 / 显式 COMMENT）。
CREATE TABLE IF NOT EXISTS config_override
(
    config_key   VARCHAR(128)  NOT NULL COMMENT '配置键（Spring 属性名，如 tgg.admin.overdue-remind-hours）',
    config_value VARCHAR(1024) NOT NULL COMMENT '覆盖值（字符串形态；仅非密钥键写入）',
    updated_at   DATETIME(6)   NOT NULL COMMENT '最后修改时间',
    updated_by   BIGINT        NULL COMMENT '操作者 userId（与 audit_log 交叉引用）',
    PRIMARY KEY (config_key),
    INDEX idx_config_override_updated_at (updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='运行期配置覆盖（Web 配置中心写入；解析顺序：本表 → 环境变量/yml 默认）';

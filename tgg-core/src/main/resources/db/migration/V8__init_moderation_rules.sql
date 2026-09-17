-- 模块九 · 按群教学规则（V5.0 §10.3 的 /teach）。
--
-- ⚠️ 新起版本号 V8，**不改既有迁移**：改已应用的文件会触发 Flyway checksum 不匹配 → 启动失败。
--
-- 表设计要点：
--   · 按群（chat_id）+ 规则 id 唯一——同一规则 id 可在不同群各自定义；
--   · 只存「正则 + 元信息」，**不存任何消息正文**（隐私红线：规则是模板，不是内容）；
--   · regex 上限 512 字符——与 ReDoS 防护的前置约束一致（见 TaughtRuleService 的校验）；
--   · enabled 支持停用而非删除（规则是审计对象，同本项目既有的「软删」纪律）。
CREATE TABLE IF NOT EXISTS moderation_rules
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    chat_id    BIGINT       NOT NULL COMMENT '生效群（Telegram chatId，负数）',
    rule_id    VARCHAR(64)  NOT NULL COMMENT '规则 id（群内唯一）',
    name       VARCHAR(128) NOT NULL COMMENT '规则描述（管理员填写）',
    regex      VARCHAR(512) NOT NULL COMMENT '正则（管理员手写；V5.0 的 DeepSeek 自动生成属接入位）',
    risk_level VARCHAR(16)  NOT NULL COMMENT 'LOW / MEDIUM / HIGH',
    hard_line  BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '硬红线：命中即冻结，不等复核',
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '停用而非删除',
    created_by BIGINT       NULL COMMENT '提交者 userId（明文，定位主体用）',
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_moderation_rules_chat_rule (chat_id, rule_id),
    KEY idx_moderation_rules_chat (chat_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='按群教学规则（/teach）';

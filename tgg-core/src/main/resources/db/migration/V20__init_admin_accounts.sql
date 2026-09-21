-- 后台账号体系（模块十一 · 账号与权限模型）——两张表：账号与会话。
--
-- 为什么需要（用户需求原话）：「对系统全部掌控、对所有账号全部管理、单独设立一个账号进行管理」。
-- 在本次改动之前，后台**零账号表**——Web 操作者身份直接来自请求头 X-Operator-Id（可任意伪造），
-- 而它同时被当作 Telegram userId 用于审计与「不可自审」。本迁移引入真实身份实体。
--
-- 三类主体、两条授权：
--   · SUPER_ADMIN —— 纯 Web 身份，**不是 TG 用户**；只能由环境变量引导创建（无任何 API 能建）。
--   · OPERATOR    —— 超管创建的账号，持部分平台能力。
--   · TG_USER     —— Telegram 登录 Widget 验签即签发会话，**不建本地账号**（不落 admin_accounts）。
--
-- ⚠️ 为什么 password 存 hash 而非明文：库被读走时明文密码会被直接复用。
--    哈希算法与格式（PBKDF2，pbkdf2$iter$saltB64$hashB64）由应用层 PasswordHasher 定义。
-- ⚠️ 为什么会话是**服务端表**而不是 JWT：用户要求「全部掌控」⇒ 改权限/封号必须**立刻生效**；
--    JWT 无法吊销，封了号旧 token 仍有效直到过期。
CREATE TABLE IF NOT EXISTS admin_accounts
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    username      VARCHAR(64)  NOT NULL COMMENT '登录名（唯一）',
    password_hash VARCHAR(255) NOT NULL COMMENT 'PBKDF2 哈希（pbkdf2$iter$saltB64$hashB64）',
    role          VARCHAR(16)  NOT NULL COMMENT '账号角色：SUPER_ADMIN / OPERATOR',
    status        VARCHAR(16)  NOT NULL COMMENT '状态：ACTIVE / DISABLED（停用即不可登录）',
    totp_secret   VARCHAR(64)  NULL COMMENT 'TOTP 密钥（Wave 5 启用；当前为 NULL）',
    failed_attempts INT        NOT NULL DEFAULT 0 COMMENT '连续登录失败次数（达阈值触发锁定）',
    locked_until  DATETIME(6)  NULL COMMENT '锁定到期时间；未锁定为 NULL',
    created_at    DATETIME(6)  NOT NULL COMMENT '创建时间',
    updated_at    DATETIME(6)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='后台账号（超管 / 操作员）';

-- 会话：登录后签发，服务端存**令牌哈希**（明文令牌只在响应里给一次，库泄露也无法反会话）。
-- subject_type + subject_id 指向主体：ADMIN_ACCOUNT 时为 admin_accounts.id，TG_USER 时为 TG userId。
-- ⚠️ 两主体数值空间可能重叠（账号 id 与 TG userId 都是 BIGINT），故必须带 subject_type 才唯一。
--    这正是 audit_log.actor_type 存在的同一条理由（见 V21）。
CREATE TABLE IF NOT EXISTS admin_sessions
(
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    token_hash   CHAR(64)    NOT NULL COMMENT '会话令牌的 SHA-256 hex（明文令牌不落库）',
    subject_type VARCHAR(16) NOT NULL COMMENT '主体类型：ADMIN_ACCOUNT / TG_USER',
    subject_id   BIGINT      NOT NULL COMMENT '主体 id（账号 id 或 TG userId）',
    created_at   DATETIME(6) NOT NULL COMMENT '签发时间',
    expires_at   DATETIME(6) NOT NULL COMMENT '到期时间',
    revoked_at   DATETIME(6) NULL COMMENT '吊销时间；非空即已失效（强制下线用）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_token (token_hash),
    INDEX idx_session_subject (subject_type, subject_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='后台会话（服务端可即时吊销）';

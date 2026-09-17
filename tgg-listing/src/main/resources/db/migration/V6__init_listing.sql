-- 模块五/六 · 收录：群组收录库 + 验证记录 + 商家 + 保证金 + 保证金流水。
--
-- 表设计要点：软删不物理删（模块五要求「内部保留软删记录」）；凭证不落原文；
-- utf8mb4；唯一键配合 INSERT IGNORE 保持幂等（沿用坑 21 的纪律）。
-- 表结构源自设计文档 docs/superpowers/specs/2026-09-17--module5-6-listing-design.md §5。

-- 群组收录库（模块五）
CREATE TABLE IF NOT EXISTS listing_groups (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    chat_id          BIGINT      NOT NULL COMMENT '群 chatId（负数）',
    invite_link      VARCHAR(255) NULL,
    title            VARCHAR(255) NULL,
    submitter_user_id BIGINT     NULL COMMENT '提交者（定位用，明文）',
    status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / SUSPENDED（软删）',
    fail_count       INT         NOT NULL DEFAULT 0,
    last_verified_at DATETIME(6) NULL,
    suspended_at     DATETIME(6) NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_listing_groups_chat (chat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 每次验证结果（模块五）
CREATE TABLE IF NOT EXISTS listing_verification_records (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    listing_id BIGINT      NOT NULL,
    verified_at DATETIME(6) NOT NULL,
    result     VARCHAR(16) NOT NULL COMMENT 'OK / FAIL / ERROR',
    detail     VARCHAR(512) NULL COMMENT '仅状态描述，不含群消息内容',
    PRIMARY KEY (id),
    KEY idx_lvr_listing (listing_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 商家（模块六）
CREATE TABLE IF NOT EXISTS merchants (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT      NOT NULL,
    name          VARCHAR(255) NOT NULL,
    category      VARCHAR(64) NULL,
    intro         TEXT        NULL,
    contact       VARCHAR(255) NULL,
    status        VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED',
    tier          VARCHAR(24) NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_merchants_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 保证金（模块六）
CREATE TABLE IF NOT EXISTS merchant_deposits (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT      NOT NULL,
    amount      DECIMAL(24,8) NOT NULL,
    currency    VARCHAR(16) NOT NULL DEFAULT 'USDT',
    state       VARCHAR(16) NOT NULL COMMENT 'PENDING/LOCKED/FROZEN/REFUNDED/DEDUCTED',
    gateway_ref VARCHAR(128) NULL COMMENT '链上引用（接入位产出），本机为 noop',
    reason      VARCHAR(512) NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_deposit_merchant (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 保证金流水（模块六）
CREATE TABLE IF NOT EXISTS merchant_deposit_records (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    deposit_id BIGINT      NOT NULL,
    action     VARCHAR(16) NOT NULL COMMENT 'PAY/FREEZE/REFUND/DEDUCT',
    amount     DECIMAL(24,8) NOT NULL,
    reason     VARCHAR(512) NULL COMMENT '扣除必填理由（V5.0 约束）',
    operator   BIGINT      NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_mdr_deposit (deposit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

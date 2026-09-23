-- 模块十二 · 担保交易：资金流水账（append-only）。
--
-- 为什么独立于通用 audit_log：audit_log 的 detail 是 VARCHAR(512) 自由文本、无金额/币种列，
-- 且其写入是弱一致（AuditService 失败只 log.error、不阻断业务）——资金流水需要的是
-- 「结构化金额 + 强一致 + 可对账」，两者语义与可靠性要求不同（G4 调研结论）。
--
-- 纪律（与 escrow_orders 同款，另加强一致）：
--   ① 只追加：无物理删除（资金流水是审计与对账依据）；
--   ② 幂等：idempotency_key 唯一约束是去重的唯一依据（同键 = 同一业务事实已记账）；
--   ③ 金额 DECIMAL(24,8) 精确十进制——资金问题，不是显示问题；
--   ④ chain_ref / chain_state 为链上接入（Wave 5）预留：未上链时 chain_state='LOCAL'、chain_ref 为 NULL。

CREATE TABLE IF NOT EXISTS escrow_fund_ledger (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    order_id        BIGINT        NULL COMMENT '关联 escrow_orders.id（罚金/基金类动作可为空）',
    direction       VARCHAR(24)   NOT NULL COMMENT 'HOLD/RELEASE/REFUND/DEDUCT_PENALTY/FUND_ADVANCE',
    amount          DECIMAL(24,8) NOT NULL COMMENT '金额（恒正数，方向由 direction 表达）',
    currency        VARCHAR(16)   NOT NULL DEFAULT 'USDT',
    subject_user_id BIGINT        NULL COMMENT '资金动作指向的用户（赔付收款方/被扣方）',
    chain_ref       VARCHAR(128)  NULL COMMENT '链上交易哈希或 jetton 引用（未上链为 NULL）',
    chain_state     VARCHAR(16)   NOT NULL DEFAULT 'LOCAL' COMMENT 'LOCAL/PENDING/CONFIRMED/FAILED',
    reason          VARCHAR(512)  NULL COMMENT '动作理由（不含私密正文）',
    idempotency_key VARCHAR(191)  NOT NULL COMMENT '业务去重键',
    occurred_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_escrow_fund_idempotency (idempotency_key),
    KEY idx_escrow_fund_order (order_id),
    KEY idx_escrow_fund_subject (subject_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 模块十二 · 担保交易：订单账本。
--
-- 表设计要点（与模块六 merchant_deposits 同款纪律）：无物理删除（订单是审计与争议裁决的依据）；
-- 金额 DECIMAL(24,8) 精确十进制（担保交易里金额是资金问题，不是显示问题）；utf8mb4。
-- 表结构源自调研报告 docs/RESEARCH-TON-MINIAPP.md §3 gap-ESC-02（W2 核心，骨架先落账本/状态机）。
--
-- 状态枚举（escrow_orders.state）与 EscrowOrder.State 一一对应：
--   OPEN ──lock──▶ LOCKED ──┬─ release ─▶ RELEASED（终态）
--        （待锁仓）        │─ refund  ─▶ REFUNDED（终态）
--                          └─ dispute ─▶ DISPUTED ──┬─ release ─▶ RELEASED（裁决归卖家）
--                              （争议中）           └─ refund  ─▶ REFUNDED（裁决归买家）

CREATE TABLE IF NOT EXISTS escrow_orders (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    buyer_user_id  BIGINT        NOT NULL COMMENT '买家 userId（明文，定位用）',
    seller_user_id BIGINT        NOT NULL COMMENT '卖家 userId（明文，定位用）',
    amount         DECIMAL(24,8) NOT NULL COMMENT '担保金额（精确十进制）',
    currency       VARCHAR(16)   NOT NULL DEFAULT 'USDT',
    state          VARCHAR(16)   NOT NULL COMMENT 'OPEN/LOCKED/DISPUTED/RELEASED/REFUNDED',
    reason         VARCHAR(512)  NULL COMMENT '争议/退款理由（仅状态描述，不含私密正文）',
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_escrow_buyer (buyer_user_id),
    KEY idx_escrow_seller (seller_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

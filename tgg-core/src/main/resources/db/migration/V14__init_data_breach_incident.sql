-- 模块十 §11.2「数据泄露 72 小时通报」（2026-09-18）。
--
-- 定位：**通报本身是运营者的法定义务**（GDPR Art. 33 等），软件不替运营者履行；
-- 软件负责的是「登记 → 计时 → 到点催办 → 留痕」——把「72 小时」这条硬约束变成可追踪的对象，
-- 而不是散落在某人的记忆里。
--
-- 只追加为主、状态可更新（reported_at 由 null 变为时间）；同审计表，**不提供删除入口**
-- （通报记录是合规证据，删掉它等于自毁证据链）。
CREATE TABLE IF NOT EXISTS data_breach_incident
(
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    detected_at    DATETIME(6)  NOT NULL COMMENT '发现时间（72 小时从此刻起算）',
    deadline_at    DATETIME(6)  NOT NULL COMMENT '通报截止（= detected_at + 72h）',
    scope          VARCHAR(255) NOT NULL COMMENT '影响范围简述（不含个人数据本身）',
    affected_count INT          NOT NULL DEFAULT 0 COMMENT '预计受影响人数',
    reported_at    DATETIME(6)  NULL COMMENT '实际通报时间；NULL = 尚未通报',
    reported_by    BIGINT       NULL COMMENT '通报操作人 userId',
    registered_by  BIGINT       NULL COMMENT '登记人 userId',
    created_at     DATETIME(6)  NOT NULL COMMENT '登记时间',
    PRIMARY KEY (id),
    INDEX idx_breach_reported_deadline (reported_at, deadline_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='数据泄露事件与 72 小时通报计时';

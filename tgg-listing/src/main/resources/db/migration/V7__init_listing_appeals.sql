-- 模块五 · 收录：失效申诉（设计文档 §4 的 ListingAppeal / §6.1 的 /listing appeal）。
--
-- ⚠️ 新增版本号 V7，**不改 V6**：V6 已在真实库应用，改动它的字节会改变 Flyway checksum，
-- 下次启动即校验失败（LESSONS 坑 8 的真实代价）。
--
-- 表设计要点：utf8mb4；申诉正文是用户【主动提交】的管理输入（非被监听的对话正文），
-- 因此落库与「消息原文零存储」不冲突；status 默认 PENDING，裁定结果留待后续增量写入。
CREATE TABLE IF NOT EXISTS listing_appeals (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    listing_id  BIGINT      NOT NULL COMMENT '被申诉的收录条目（listing_groups.id）',
    user_id     BIGINT      NOT NULL COMMENT '申诉人 user id（提交者本人，明文，定位主体用）',
    appeal_text TEXT        NULL COMMENT '申诉理由（用户主动提交；不进日志、不进审核判定）',
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_listing_appeals_listing (listing_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

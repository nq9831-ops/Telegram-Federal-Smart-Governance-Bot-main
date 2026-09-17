-- 模块九 §10.4 · 人工复核的「裁决 / 操作员推翻权」。
--
-- 本迁移**只新增列**，不改 V2 已有的任何列或约束——按本项目纪律，已应用的迁移文件不可改
-- （改注释都会触发 checksum 校验失败，见 docs/LESSONS.md 坑 8），故裁决相关字段一律走新迁移。
--
-- hard_line：命中硬红线的条目**也入队**（供操作员事后推翻误封 → 解封），故需显式标记；
--           否则队列里分不清「已自动封禁的」与「仅删除待复核的」，推翻权无从落地。
-- decided_by / decided_at / note：裁决审计——谁、何时、凭什么改判（note 不含正文）。
ALTER TABLE moderation_review_queue
    ADD COLUMN hard_line  BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '是否命中硬红线（自动封禁；可被操作员推翻解封）',
    ADD COLUMN decided_by BIGINT       NULL COMMENT '裁决人 userId（全局白名单）',
    ADD COLUMN decided_at DATETIME(6)  NULL COMMENT '裁决时间',
    ADD COLUMN note       VARCHAR(255) NULL COMMENT '裁决备注（不含正文）';

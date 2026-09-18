-- 模块九 §10.3 教学门槛 · 成员入群时间观察（2026-09-18）。
--
-- 为什么需要它：§10.3 三条门槛里的「入群时长 ≥30 天」全仓无数据源。
--   Telegram Bot API **不提供**「查询某成员何时入群」的接口（getChatMember 只给当前状态，
--   restricted 除外也拿不到时间），只有一个途径：**chat_member 更新**——
--   成员状态变化时 Telegram 会推送该事件。故只能被动采集：
--   每次收到 chat_member 更新时记下「该成员此刻在群」，首次记录即最近一次入群时间。
--
-- 数据最小化（本表是刻意的窄表，与「数据最小化」原则的张力在此处被压到最小）：
--   ① 只存 (chat_id, user_id, joined_at, observed_at)——**不存**用户名/昵称/邀请链接/邀请人；
--   ② **退群即删**（new 状态为 left/kicked 时删除该行）——表内始终只有「当前在群成员」，
--      不留历史 membership 轨迹；
--   ③ 纳入保留策略（`tgg.retention.membership-days`，默认 365 天，与违规计数同口径）。
--
-- 语义边界（写清楚，避免误用）：
--   `joined_at` 是「**观察到**的入群时间」，**不是** Telegram 的权威入群时间——
--   bot 成为群管理员之前就已入群的成员**不会有行**（除非其状态之后再次变化）。
--   消费方必须显式处理「无行」的情形（见 MembershipDurationTeachGate 的口径）。
CREATE TABLE IF NOT EXISTS member_join_observations
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    chat_id     BIGINT      NOT NULL COMMENT '群组 ID（Telegram 群 ID 为负数）',
    user_id     BIGINT      NOT NULL COMMENT '成员 ID（取自 new_chat_member.getUser()，**不是**事件触发者 from）',
    joined_at   DATETIME(6) NOT NULL COMMENT '最近一次观察到入群的时间（UTC）——已有行不覆盖',
    observed_at DATETIME(6) NOT NULL COMMENT '最后一次写入/更新时间（UTC，供保留策略）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_join_chat_user (chat_id, user_id),
    KEY idx_member_join_observed (observed_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='成员入群时间观察（§10.3 教学门槛「入群时长」的数据源；退群即删）';

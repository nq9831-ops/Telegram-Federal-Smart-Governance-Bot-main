package com.tg.heyisheng.bot.core.platform;

import java.util.Set;

/**
 * 「业务表 → 作者列」映射（模块十一 · 数据范围）。
 *
 * <p>三套命名（{@code submitter_user_id} / {@code owner_user_id} / {@code created_by}）在此<b>一处收敛</b>，
 * 使「按作者过滤」只有一个事实来源——否则每张表各写一遍条件，迟早漂移。
 *
 * <p><b>⚠️ 绝不可按列名 {@code user_id} 统一过滤</b>：库里另有若干表也用 {@code user_id}，
 * 但那个列是<b>被判定者 / 成员</b>，不是作者——按列名过滤会把「改我写的」变成「改我被判定的」，
 * 语义正好反了。故这些表<b>不在</b>本枚举里，并由 {@link #NON_AUTHOR_USER_ID_TABLES} 显式登记，
 * 供审查与测试断言「没有被误纳入」。
 */
public enum AuthorColumn {

    /** 群组收录：提交者。 */
    LISTING_GROUPS("listing_groups", "submitter_user_id"),

    /** 商家收录：所有者。 */
    MERCHANTS("merchants", "owner_user_id"),

    /** 联邦申诉：申诉人。 */
    FEDERATION_APPEALS("federation_appeals", "user_id"),

    /** 审核规则：创建者。 */
    MODERATION_RULES("moderation_rules", "created_by"),

    /** 违禁词：创建者。 */
    BANNED_WORDS("banned_words", "created_by"),

    /** 群话题标签：创建者。 */
    GROUP_TOPIC_TAGS("group_topic_tags", "created_by");

    /**
     * 这些表也有 {@code user_id} 列，但语义是<b>被判定者 / 成员</b>，<b>不是作者</b>。
     * 显式登记以防将来有人「按列名统一过滤」。
     */
    public static final Set<String> NON_AUTHOR_USER_ID_TABLES =
            Set.of("moderation_review_queue", "sensitive_topic_strikes", "member_join_observations");

    private final String table;
    private final String column;

    AuthorColumn(String table, String column) {
        this.table = table;
        this.column = column;
    }

    /** 表名。 */
    public String table() {
        return table;
    }

    /** 作者列名。 */
    public String column() {
        return column;
    }
}

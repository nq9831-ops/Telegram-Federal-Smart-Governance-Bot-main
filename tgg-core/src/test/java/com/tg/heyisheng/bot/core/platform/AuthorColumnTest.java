package com.tg.heyisheng.bot.core.platform;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「业务表 → 作者列」映射。
 *
 * <p>守的是<b>语义不反转</b>：{@code moderation_review_queue.user_id} 等是「被判定者 / 成员」，
 * 不是作者——它们绝不能被当成作者列（否则「改我写的」会变成「改我被判定的」）。
 */
class AuthorColumnTest {

    @Test
    void mapsAllSixAuthoredTables() {
        List<String> tables = Arrays.stream(AuthorColumn.values()).map(AuthorColumn::table).toList();

        assertThat(tables).containsExactlyInAnyOrder(
                "listing_groups", "merchants", "federation_appeals",
                "moderation_rules", "banned_words", "group_topic_tags");
    }

    @Test
    void subjectTablesAreNotTreatedAsAuthored() {
        List<String> tables = Arrays.stream(AuthorColumn.values()).map(AuthorColumn::table).toList();

        assertThat(tables)
                .as("被判定者 / 成员所在的表绝不可当作「作者」表")
                .doesNotContainAnyElementsOf(AuthorColumn.NON_AUTHOR_USER_ID_TABLES);
    }

    @Test
    void declaredNonAuthorTablesDoUseUserIdColumn() {
        // 这些表用 user_id 存被判定者——正因如此才要显式排除，防止「按列名统一过滤」
        assertThat(AuthorColumn.NON_AUTHOR_USER_ID_TABLES)
                .containsExactlyInAnyOrder(
                        "moderation_review_queue", "sensitive_topic_strikes", "member_join_observations");
    }
}

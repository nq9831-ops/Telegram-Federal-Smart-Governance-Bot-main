package com.tg.heyisheng.bot.core.moderation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感话题违规计数的真库测试（模块九 §10.5）——直连本机 MySQL 测试库，非 H2。
 *
 * <p>守的是 V11 迁移与「DB 侧 upsert」：表是否真建、唯一键是否真生效、
 * 累加是否真落在同一行（用 `ON DUPLICATE KEY UPDATE` 而非「先查再存 / catch 异常」——
 * 后者在 Hibernate 下会把 session 打成不可用，本项目已踩过）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SensitiveTopicStrikeService.class)
class SensitiveTopicStrikeServiceIT {

    private static final long CHAT = -1002000000004L;
    private static final long USER = 9001L;

    @Autowired
    private SensitiveTopicStrikeRepository repository;

    @Autowired
    private SensitiveTopicStrikeService service;

    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    @Test
    void countsPerUserAndPerGroup() {
        assertThat(service.record(CHAT, USER)).as("首次").isEqualTo(1);
        assertThat(service.record(CHAT, USER)).isEqualTo(2);
        assertThat(service.record(CHAT, USER)).isEqualTo(3);
        assertThat(service.countOf(CHAT, USER)).isEqualTo(3);

        assertThat(service.countOf(CHAT, USER + 1)).as("换个人从 0 起").isZero();
        assertThat(service.record(CHAT - 1, USER)).as("换个群从 1 起").isEqualTo(1);
    }

    @Test
    void keepsSingleRowPerUserPerGroup() {
        service.record(CHAT, USER);
        service.record(CHAT, USER);

        assertThat(repository.findAll()).as("同人同群只有一行").hasSize(1);
        assertThat(repository.findByChatIdAndUserId(CHAT, USER).orElseThrow().getStrikeCount())
                .isEqualTo(2);
    }
}

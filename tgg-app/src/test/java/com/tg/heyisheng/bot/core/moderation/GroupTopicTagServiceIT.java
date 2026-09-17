package com.tg.heyisheng.bot.core.moderation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 群话题标签的真库测试（模块九 §10.5）——直连本机 MySQL 测试库，非 H2。
 *
 * <p>V10 迁移是否真建表、唯一键是否真生效、审计字段是否真落库——mock 证明不了。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(GroupTopicTagService.class)
class GroupTopicTagServiceIT {

    private static final long CHAT = -1002000000003L;

    @Autowired
    private GroupTopicTagRepository repository;

    @Autowired
    private GroupTopicTagService service;

    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    @Test
    void addListAndRemovePersistToRealDatabase() {
        assertThat(service.add(CHAT, "gambling", 42L)).isTrue();
        assertThat(service.add(CHAT, "Gambling", 42L))
                .as("规范化后同标签 → 幂等，不产生第二行").isFalse();

        assertThat(service.listOf(CHAT)).extracting(GroupTopicTag::getTag).containsExactly("gambling");
        assertThat(service.listOf(CHAT).get(0).getCreatedBy()).as("审计字段落库").isEqualTo(42L);
        assertThat(service.listOf(CHAT).get(0).getCreatedAt()).isNotNull();

        assertThat(service.remove(CHAT, "gambling")).isTrue();
        assertThat(service.listOf(CHAT)).isEmpty();
        assertThat(service.remove(CHAT, "gambling")).as("再删同一标签应返回 false").isFalse();
    }

    @Test
    void tagsAreScopedPerGroup() {
        service.add(CHAT, "gambling", 42L);

        assertThat(service.tagsOf(CHAT)).containsExactly("gambling");
        assertThat(service.tagsOf(CHAT - 1)).as("标签只对本群生效").isEmpty();
    }
}

package com.tg.heyisheng.bot.core.groupconfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 群组配置持久化测试 —— <b>直连本机 MySQL</b>，非 H2。
 *
 * <p>取舍：H2 的 MySQL 兼容模式不等于 MySQL，用它测会掩盖方言差异
 * （utf8mb4、DATETIME(6) 精度、负数 BIGINT 主键等）。
 * 代价是测试依赖本机 MySQL 在跑，隔离靠 {@code @DataJpaTest} 自带的事务回滚。
 *
 * <p>{@code replace = NONE} 是必需的：{@code @DataJpaTest} 默认会用内存库替换数据源，
 * 那样就测不到真实 MySQL 了。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// @DataJpaTest 是「切片测试」，只装配 JPA 相关 bean，不会创建 @Service。
// 需要显式导入才能测到服务层。
@org.springframework.context.annotation.Import(GroupConfigService.class)
class GroupConfigPersistenceIT {

    private static final long CHAT_ID = -1001234567890L;

    @Autowired
    private GroupConfigRepository repository;

    @Autowired
    private GroupConfigService service;

    @Test
    void savesAndFindsByNegativeChatId() {
        repository.save(new GroupConfig(CHAT_ID, "测试群"));

        assertThat(repository.findById(CHAT_ID))
                .as("负数 BIGINT 主键必须能正确往返") 
                .isPresent()
                .get()
                .extracting(GroupConfig::getChatId, GroupConfig::getTitle)
                .containsExactly(CHAT_ID, "测试群");
    }

    @Test
    void unknownChatIdFallsBackToDefaultWithoutError() {
        GroupConfigView view = service.findOrDefault(-999999999L);

        assertThat(view.enabled()).as("未登记群组默认启用").isTrue();
        assertThat(view.chatId()).isEqualTo(-999999999L);
    }

    @Test
    void registerIsIdempotentAndUpdatesTitle() {
        service.register(CHAT_ID, "旧名");
        long countAfterFirst = repository.count();

        service.register(CHAT_ID, "新名");

        // 不硬编码行数：库里可能已有其它数据，断言"注册前后行数不变"才可靠
        assertThat(repository.count()).as("重复登记不应新增行").isEqualTo(countAfterFirst);
        assertThat(repository.findById(CHAT_ID).orElseThrow().getTitle()).isEqualTo("新名");
    }

    @Test
    void setEnabledPersistsAndIsReadBack() {
        service.register(CHAT_ID, "测试群");

        service.setEnabled(CHAT_ID, false);
        assertThat(service.findOrDefault(CHAT_ID).enabled()).isFalse();

        service.setEnabled(CHAT_ID, true);
        assertThat(service.findOrDefault(CHAT_ID).enabled()).isTrue();
    }

    @Test
    void storesEmojiInTitleWithoutCorruption() {
        // utf8mb4 验证：用 utf8（3 字节）建库时 emoji 会被截断或报错
        String titleWithEmoji = "测试群 🎉🚀";

        service.register(CHAT_ID, titleWithEmoji);

        assertThat(repository.findById(CHAT_ID).orElseThrow().getTitle())
                .as("emoji 必须原样往返——这验证了 utf8mb4 字符集确实生效")
                .isEqualTo(titleWithEmoji);
    }
}

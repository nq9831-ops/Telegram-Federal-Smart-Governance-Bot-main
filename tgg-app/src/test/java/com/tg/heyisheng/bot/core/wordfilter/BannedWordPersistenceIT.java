package com.tg.heyisheng.bot.core.wordfilter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.Commit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 违禁词持久化测试 —— 直连本机 MySQL（与 GroupConfigPersistenceIT 同取舍）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(BannedWordService.class)
class BannedWordPersistenceIT {

    private static final long CHAT_A = -1004000000001L;
    private static final long CHAT_B = -1004000000002L;

    @Autowired
    private BannedWordRepository repository;

    @Autowired
    private BannedWordService service;

    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    /**
     * 幂等的**真实数据库**验证：第二次 add 必须返回 false，且**不得抛异常**。
     *
     * <p>这条覆盖 mock 测不到的部分——{@code @Transactional} 代理、Hibernate flush、
     * 以及唯一约束冲突后的**提交阶段**。用 {@code @Commit} 让事务真的提交：
     * 若 {@code addWord} 没有声明 {@code noRollbackFor}，冲突会把事务标记为 rollback-only，
     * 提交时抛 {@code UnexpectedRollbackException}，本用例就会红。
     */
    @Test
    @Commit
    void addIsIdempotentOnRealDatabase() {
        assertThat(service.addWord(CHAT_A, "spam", 42L)).as("首次添加应成功").isTrue();
        assertThat(service.addWord(CHAT_A, "spam", 42L)).as("重复添加应返回 false 而非抛异常").isFalse();
        assertThat(service.listWords(CHAT_A)).as("只应存在一条").containsExactly("spam");
    }

    @Test
    void addListRemoveRoundTrip() {
        assertThat(service.addWord(CHAT_A, "spam", 42L)).isTrue();
        assertThat(service.listWords(CHAT_A)).containsExactly("spam");

        assertThat(service.addWord(CHAT_A, "spam", 42L)).as("重复添加幂等").isFalse();

        assertThat(service.removeWord(CHAT_A, "spam")).isTrue();
        assertThat(service.listWords(CHAT_A)).isEmpty();
    }

    @Test
    void wordsAreIsolatedPerChat() {
        service.addWord(CHAT_A, "spam", 42L);

        assertThat(service.listWords(CHAT_B))
                .as("词表必须按群隔离——B 群不因 A 群加词而被影响")
                .isEmpty();
    }

    @Test
    void uniquenessIsCaseInsensitiveUnderCollation() {
        service.addWord(CHAT_A, "Spam", 42L);

        assertThat(service.addWord(CHAT_A, "spam", 42L))
                .as("utf8mb4_unicode_ci 下大小写视为同一条，不应重复写入")
                .isFalse();
    }
}

package com.tg.heyisheng.bot.core.wordfilter;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 违禁词服务单元测试（不依赖数据库）。
 *
 * <p>核心两条：读词表 fail-open（失败不阻断消息处理）；写词幂等（撞唯一约束视作已存在）。
 * 真实数据库上的写入行为由 {@code BannedWordPersistenceIT} 覆盖。
 */
class BannedWordServiceTest {

    private static final long CHAT = -100L;

    @Test
    void listFailsOpenWhenRepositoryThrows() {
        BannedWordRepository failing = mock(BannedWordRepository.class);
        when(failing.findByChatId(any())).thenThrow(new RuntimeException("db down"));

        BannedWordService service = new BannedWordService(failing);

        assertThat(service.listWords(CHAT)).as("读词表失败时按无词表放行，不得抛出").isEmpty();
    }

    @Test
    void addRejectsBlankOverlongAndNullChat() {
        BannedWordService service = new BannedWordService(mock(BannedWordRepository.class));

        assertThat(service.addWord(CHAT, "   ", 1L)).isFalse();
        assertThat(service.addWord(CHAT, "x".repeat(256), 1L)).as("超长应拒绝（列宽 255）").isFalse();
        assertThat(service.addWord(null, "spam", 1L)).isFalse();
    }

    /** 存储前必须归一化（去首尾空白），否则 "  spam" 与 "spam" 会绕过去重。 */
    @Test
    void addStoresNormalizedWord() {
        BannedWordRepository repo = mock(BannedWordRepository.class);
        when(repo.insertIgnore(any(), any(), any(), any())).thenReturn(1);
        BannedWordService service = new BannedWordService(repo);

        assertThat(service.addWord(CHAT, "  spam  ", 1L)).isTrue();

        org.mockito.ArgumentCaptor<String> word = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repo).insertIgnore(org.mockito.ArgumentMatchers.eq(CHAT), word.capture(),
                org.mockito.ArgumentMatchers.eq(1L), any());
        assertThat(word.getValue()).isEqualTo("spam");
    }

    /**
     * 幂等：数据库忽略冲突（受影响行数 0）即表示该词已存在，返回 false。
     *
     * <p>注意本用例只验证返回值映射；<b>真实数据库上的冲突行为与"不污染 session"</b>
     * 由 {@code BannedWordPersistenceIT} 覆盖。
     */
    @Test
    void addReturnsFalseWhenDatabaseIgnoredTheInsert() {
        BannedWordRepository repo = mock(BannedWordRepository.class);
        when(repo.insertIgnore(any(), any(), any(), any())).thenReturn(0);

        BannedWordService service = new BannedWordService(repo);

        assertThat(service.addWord(CHAT, "spam", 1L)).as("已存在时应返回 false").isFalse();
    }

    @Test
    void removeReturnsFalseWhenAbsent() {
        BannedWordRepository repo = mock(BannedWordRepository.class);
        when(repo.deleteByChatIdAndWord(CHAT, "spam")).thenReturn(0L);

        BannedWordService service = new BannedWordService(repo);

        assertThat(service.removeWord(CHAT, "spam")).as("本群没有该词时应返回 false").isFalse();
    }
}

package com.tg.heyisheng.bot.core.wordfilter;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 违禁词服务单元测试（不依赖数据库）。
 *
 * <p>核心是 fail-open：读词表失败必须按「无词表」放行，而非抛异常中断消息处理。
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

    @Test
    void addIsIdempotentAfterNormalization() {
        BannedWordRepository repo = mock(BannedWordRepository.class);
        when(repo.existsByChatIdAndWord(CHAT, "spam")).thenReturn(true);

        BannedWordService service = new BannedWordService(repo);

        assertThat(service.addWord(CHAT, "  spam  ", 1L))
                .as("去空白后与既有词相同则不再新增").isFalse();
    }

    @Test
    void addSavesNormalizedWord() {
        BannedWordRepository repo = mock(BannedWordRepository.class);
        when(repo.existsByChatIdAndWord(CHAT, "spam")).thenReturn(false);

        BannedWordService service = new BannedWordService(repo);

        assertThat(service.addWord(CHAT, "  spam  ", 1L)).isTrue();
    }

    /**
     * 并发下第二个请求会撞 (chat_id, word) 唯一约束——语义等同"已存在"，
     * 必须返回 false 而不是把异常抛给命令层（那会让 /addword 直接失败，破坏幂等契约）。
     */
    @Test
    void addTreatsUniqueConstraintViolationAsAlreadyExists() {
        BannedWordRepository repo = mock(BannedWordRepository.class);
        when(repo.existsByChatIdAndWord(CHAT, "spam")).thenReturn(false);
        when(repo.save(any(BannedWord.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        BannedWordService service = new BannedWordService(repo);

        assertThat(service.addWord(CHAT, "spam", 1L))
                .as("撞唯一约束应按已存在处理，返回 false 而非抛异常").isFalse();
    }

    @Test
    void removeReturnsFalseWhenAbsent() {
        BannedWordRepository repo = mock(BannedWordRepository.class);
        when(repo.deleteByChatIdAndWord(CHAT, "spam")).thenReturn(0L);

        BannedWordService service = new BannedWordService(repo);

        assertThat(service.removeWord(CHAT, "spam")).as("本群没有该词时应返回 false").isFalse();
    }
}

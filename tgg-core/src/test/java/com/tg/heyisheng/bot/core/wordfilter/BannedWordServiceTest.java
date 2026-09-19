package com.tg.heyisheng.bot.core.wordfilter;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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

    // ---------- 缓存（解 P2#1 热路径查库）与 last-known 回退 ----------

    @Test
    void listWordsHitsCacheWithinTtl() {
        BannedWordRepository repo = mock(BannedWordRepository.class);
        when(repo.findByChatId(CHAT)).thenReturn(List.of(new BannedWord(CHAT, "spam", 1L)));
        BannedWordService service = new BannedWordService(repo, fixedClock());

        service.listWords(CHAT);
        service.listWords(CHAT);
        service.listWords(CHAT);

        verify(repo, times(1)).findByChatId(CHAT);
    }

    @Test
    void writeInvalidatesCacheSoChangeTakesEffectImmediately() {
        BannedWordRepository repo = mock(BannedWordRepository.class);
        when(repo.findByChatId(CHAT)).thenReturn(List.of(new BannedWord(CHAT, "spam", 1L)));
        when(repo.insertIgnore(any(), any(), any(), any())).thenReturn(1);
        BannedWordService service = new BannedWordService(repo, fixedClock());

        service.listWords(CHAT);            // 预热缓存
        service.addWord(CHAT, "scam", 1L);  // 写后必须立即失效
        service.listWords(CHAT);            // 应重新查库

        verify(repo, times(2)).findByChatId(CHAT);
    }

    /**
     * 核心不变量：读失败时回退<b>上次已知词表</b>，而不是空表——
     * 空表在审核路径上等于违禁词全部放行，这正是本次修复的方向。
     */
    @Test
    void listWordsFallsBackToLastKnownWhenRepositoryFailsAfterCacheExpiry() {
        BannedWordRepository repo = mock(BannedWordRepository.class);
        when(repo.findByChatId(CHAT)).thenReturn(List.of(new BannedWord(CHAT, "spam", 1L)));
        MutableClock clock = new MutableClock(Instant.parse("2026-09-20T00:00:00Z"));
        BannedWordService service = new BannedWordService(repo, clock);

        assertThat(service.listWords(CHAT)).containsExactly("spam");

        clock.advance(BannedWordService.CACHE_TTL.plusSeconds(1)); // 缓存过期，但条目仍是 last-known
        when(repo.findByChatId(CHAT)).thenThrow(new RuntimeException("db down"));

        assertThat(service.listWords(CHAT))
                .as("读失败应回退上次已知词表，绝不因 DB 抖动让全群违禁词放行")
                .containsExactly("spam");
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC);
    }

    /** 可控时钟：让「缓存过期后的 last-known 回退」可被直接验证（固定时钟无法推进时间）。 */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration delta) {
            now = now.plus(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

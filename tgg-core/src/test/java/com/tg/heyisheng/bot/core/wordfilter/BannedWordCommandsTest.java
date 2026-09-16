package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 违禁词管理命令的行为测试。
 *
 * <p>重点是<b>操作数真的送达了服务</b>——这是 {@code UpdateContext.commandArgs()}
 * 这个受限例外存在的唯一理由；若参数丢失，命令会静默退化成"永远提示用法"。
 */
class BannedWordCommandsTest {

    private static final long CHAT = -100L;
    private static final long USER = 42L;

    private final BannedWordService service = mock(BannedWordService.class);
    private final AddWordCommandHandler addWord = new AddWordCommandHandler(service);
    private final DelWordCommandHandler delWord = new DelWordCommandHandler(service);
    private final WordsCommandHandler words = new WordsCommandHandler(service);

    @Test
    void addWordPassesArgsToService() {
        when(service.addWord(CHAT, "广告", USER)).thenReturn(true);

        assertThat(textOf(addWord.handle(ctx("广告")))).isEqualTo(AddWordCommandHandler.ADDED);
        verify(service).addWord(CHAT, "广告", USER);
    }

    @Test
    void addWordWithoutArgsShowsUsageAndTouchesNothing() {
        assertThat(textOf(addWord.handle(ctx(null)))).isEqualTo(AddWordCommandHandler.USAGE);
        verifyNoInteractions(service);
    }

    @Test
    void addWordReportsWhenAlreadyPresent() {
        when(service.addWord(CHAT, "广告", USER)).thenReturn(false);

        assertThat(textOf(addWord.handle(ctx("广告")))).isEqualTo(AddWordCommandHandler.IGNORED);
    }

    @Test
    void delWordPassesArgsAndReportsNotFound() {
        when(service.removeWord(CHAT, "广告")).thenReturn(false);

        assertThat(textOf(delWord.handle(ctx("广告")))).isEqualTo(DelWordCommandHandler.NOT_FOUND);
        verify(service).removeWord(CHAT, "广告");
    }

    @Test
    void wordsListsConfiguredWords() {
        when(service.listWords(CHAT)).thenReturn(List.of("广告", "刷单"));

        assertThat(textOf(words.handle(ctx(null)))).isEqualTo(WordsCommandHandler.PREFIX + "广告、刷单");
    }

    @Test
    void wordsShowsHintWhenEmpty() {
        when(service.listWords(CHAT)).thenReturn(List.of());

        assertThat(textOf(words.handle(ctx(null)))).isEqualTo(WordsCommandHandler.EMPTY);
    }

    private static UpdateContext ctx(String args) {
        return new UpdateContext(1, USER, CHAT, 9, "addword", args);
    }

    private static String textOf(BotApiMethod<?> method) {
        return ((SendMessage) method).getText();
    }
}

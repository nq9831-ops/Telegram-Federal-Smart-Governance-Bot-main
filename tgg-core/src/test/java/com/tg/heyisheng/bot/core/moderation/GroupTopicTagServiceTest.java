package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 群话题标签服务与命令单测（模块九 §10.5）。
 *
 * <p>守：标签规范化、幂等添加、缓存（热路径不得每消息查库）、以及命令层的输入回显。
 */
class GroupTopicTagServiceTest {

    private static final long CHAT = -100900800L;
    private static final Instant NOW = Instant.parse("2026-09-19T00:00:00Z");

    private final GroupTopicTagRepository repository = mock(GroupTopicTagRepository.class);
    private final GroupTopicTagService service =
            new GroupTopicTagService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    private static GroupTopicTag tag(String value) {
        return new GroupTopicTag(CHAT, value, 42L, NOW);
    }

    private static UpdateContext ctx(String args) {
        return new UpdateContext(1, 42L, CHAT, 5, "group_tag", args);
    }

    private static String text(Object reply) {
        return ((SendMessage) reply).getText();
    }

    @Test
    void normalizesAndValidatesTag() {
        assertThat(GroupTopicTagService.requireTag("  Gambling ")).isEqualTo("gambling");
        assertThatThrownBy(() -> GroupTopicTagService.requireTag("  ")).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> GroupTopicTagService.requireTag("bad tag!"))
                .as("含空白/符号的标签必须被拒").isInstanceOf(TggException.class);
        assertThatThrownBy(() -> GroupTopicTagService.requireTag("x".repeat(33)))
                .as("超长标签必须被拒（列宽 32）").isInstanceOf(TggException.class);
    }

    @Test
    void addIsIdempotent() {
        when(repository.findByChatIdAndTag(CHAT, "gambling")).thenReturn(Optional.of(tag("gambling")));

        assertThat(service.add(CHAT, "GAMBLING", 42L)).as("已存在 → false（不是失败）").isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    void addNewTagSavesWithAuditFields() {
        when(repository.findByChatIdAndTag(CHAT, "adult")).thenReturn(Optional.empty());

        assertThat(service.add(CHAT, "adult", 42L)).isTrue();
        verify(repository).save(any(GroupTopicTag.class));
    }

    @Test
    void removeReportsMissing() {
        when(repository.findByChatIdAndTag(CHAT, "adult")).thenReturn(Optional.empty());

        assertThat(service.remove(CHAT, "adult")).isFalse();
        verify(repository, never()).delete(any());
    }

    @Test
    void tagsOfCachesWithinTtl() {
        when(repository.findByChatIdOrderByIdAsc(CHAT)).thenReturn(List.of(tag("gambling")));

        service.tagsOf(CHAT);
        service.tagsOf(CHAT);
        service.tagsOf(CHAT);

        verify(repository, times(1)).findByChatIdOrderByIdAsc(CHAT);
    }

    @Test
    void addInvalidatesCacheSoExemptionTakesEffectImmediately() {
        when(repository.findByChatIdOrderByIdAsc(CHAT)).thenReturn(List.of());
        service.tagsOf(CHAT); // 预热缓存

        when(repository.findByChatIdAndTag(CHAT, "gambling")).thenReturn(Optional.empty());
        service.add(CHAT, "gambling", 42L);
        service.tagsOf(CHAT);

        verify(repository, times(2)).findByChatIdOrderByIdAsc(CHAT);
    }

    // ---------- 命令层 ----------

    @Test
    void commandAddsExemptableTopicAndReports() {
        when(repository.findByChatIdAndTag(CHAT, "politics")).thenReturn(Optional.empty());

        assertThat(text(new GroupTagCommandHandler(service).handle(ctx("add politics"))))
                .contains("已声明").contains("politics");
    }

    /** 审查抓到的 HIGH：对**非已定义话题**的标签必须诚实说明「不会产生豁免」，不得谎报已生效。 */
    @Test
    void commandWarnsWhenTagIsNotAnExemptableTopic() {
        when(repository.findByChatIdAndTag(CHAT, "gambling")).thenReturn(Optional.empty());

        String reply = text(new GroupTagCommandHandler(service).handle(ctx("add gambling")));

        assertThat(reply).contains("不会产生任何豁免");
        assertThat(reply).as("应告知可豁免话题，便于群管自行发现合法标签名").contains("politics");
    }

    @Test
    void commandListsDeclaredTags() {
        when(repository.findByChatIdOrderByIdAsc(CHAT)).thenReturn(List.of(tag("gambling"), tag("adult")));

        String reply = text(new GroupTagCommandHandler(service).handle(ctx("list")));

        assertThat(reply).contains("gambling").contains("adult");
    }

    @Test
    void commandWithoutArgsShowsUsage() {
        assertThat(text(new GroupTagCommandHandler(service).handle(ctx(null)))).contains("用法");
    }

    @Test
    void commandRejectsPrivateChat() {
        UpdateContext privateChat = new UpdateContext(1, 42L, 42L, 5, "group_tag", "list");

        assertThat(text(new GroupTagCommandHandler(service).handle(privateChat))).contains("群里");
    }

    @Test
    void commandReportsInvalidTagWithoutSwallowing() {
        assertThat(text(new GroupTagCommandHandler(service).handle(ctx("add bad tag!"))))
                .contains("未生效").contains("只允许");
    }
}

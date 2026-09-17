package com.tg.heyisheng.bot.core.moderation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 敏感话题递进处置测试（模块九 §10.5）——处置按**用户累计次数**递进，不是按话题等级。
 *
 * <p>V5.0 原文：首次删除+警告 / 二次禁言 24h / 三次联邦标记。删除由 enforcer 保底，
 * 本类只守「额外的主动动作」（警告 / 禁言）与「不误伤」（豁免、无 userId、普通内容）。
 */
class SensitiveTopicGuardTest {

    private static final long CHAT = -100900900L;
    private static final long USER = 555L;
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");

    private final GroupTopicTagService tags = mock(GroupTopicTagService.class);
    private final SensitiveTopicStrikeService strikes = mock(SensitiveTopicStrikeService.class);
    private final List<BotApiMethod<?>> sent = new ArrayList<>();
    private final SensitiveTopicGuard guard = new SensitiveTopicGuard(
            new SensitiveTopicDetector(tags), strikes, sent::add, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void noDeclaredTags() {
        when(tags.tagsOf(CHAT)).thenReturn(Set.of());
    }

    @Test
    void firstStrikeWarnsWithoutMuting() {
        when(strikes.record(CHAT, USER)).thenReturn(1);

        SensitiveTopicGuard.Outcome outcome = guard.handle(CHAT, USER, "组织游行抗议").orElseThrow();

        assertThat(outcome.strike()).isEqualTo(1);
        assertThat(outcome.muted()).as("首次不该禁言").isFalse();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).isInstanceOf(SendMessage.class);
    }

    @Test
    void secondStrikeMutesFor24h() {
        when(strikes.record(CHAT, USER)).thenReturn(2);

        SensitiveTopicGuard.Outcome outcome = guard.handle(CHAT, USER, "组织游行抗议").orElseThrow();

        assertThat(outcome.muted()).isTrue();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).isInstanceOf(RestrictChatMember.class);
        assertThat(((RestrictChatMember) sent.get(0)).getUntilDate())
                .as("禁言 24h")
                .isEqualTo((int) NOW.plus(Duration.ofHours(24)).getEpochSecond());
    }

    @Test
    void thirdStrikeReachesFederationTierAndStillMutes() {
        when(strikes.record(CHAT, USER)).thenReturn(3);

        SensitiveTopicGuard.Outcome outcome = guard.handle(CHAT, USER, "组织游行抗议").orElseThrow();

        assertThat(outcome.strike()).isEqualTo(3);
        assertThat(outcome.muted()).isTrue();
    }

    @Test
    void exemptedTopicProducesNoActionAndNoStrike() {
        when(tags.tagsOf(CHAT)).thenReturn(Set.of("politics"));

        assertThat(guard.handle(CHAT, USER, "组织游行抗议")).isEmpty();
        verify(strikes, never()).record(anyLong(), anyLong());
        assertThat(sent).isEmpty();
    }

    @Test
    void missingUserIdSkipsCountingAndPersonalAction() {
        SensitiveTopicGuard.Outcome outcome = guard.handle(CHAT, null, "组织游行抗议").orElseThrow();

        assertThat(outcome.strike()).isZero();
        verify(strikes, never()).record(anyLong(), anyLong());
        assertThat(sent).as("无 userId 时不针对个人处置（删除仍由 enforcer 保底）").isEmpty();
    }

    @Test
    void ordinaryContentIsNoOp() {
        assertThat(guard.handle(CHAT, USER, "今天天气不错")).isEmpty();
        assertThat(sent).isEmpty();
    }
}

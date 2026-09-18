package com.tg.heyisheng.bot.core.membership;

import com.tg.heyisheng.bot.common.util.IdHasher;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberBanned;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberLeft;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberUpdated;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 成员入群时间采集（模块九 §10.3 数据源）。
 *
 * <p>重点钉住两个<b>静默出错</b>的方向——它们都不会抛异常，只会让门槛悄悄失效或误伤：
 * <ol>
 *   <li><b>记错人</b>：用 {@code from}（触发者）而不是 {@code new_chat_member.getUser()}。
 *       典型场景是管理员踢人：那样会把管理员的入群时间写成被踢者的。</li>
 *   <li><b>刷新入群时间</b>：状态变化（晋升/禁言）也走 chat_member 更新，
 *       若在「在群」分支做 upsert 覆盖，一次晋升就把入群时长清零，门槛形同虚设。</li>
 * </ol>
 */
class MemberJoinRecorderTest {

    private static final long CHAT = -100900999L;
    private static final long MEMBER = 777L;
    private static final long ADMIN = 555L;
    private static final int EVENT_EPOCH_SECONDS = 1_789_000_000;
    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final MemberJoinObservationRepository repository = mock(MemberJoinObservationRepository.class);
    private final MemberJoinRecorder recorder =
            new MemberJoinRecorder(repository, IdHasher.fromEnvironment(), Clock.fixed(NOW, ZoneOffset.UTC));

    private static User user(long id) {
        return User.builder().id(id).firstName("u").isBot(false).build();
    }

    private static ChatMemberUpdated event(User actor, ChatMember updated, Integer date) {
        return ChatMemberUpdated.builder()
                .chat(Chat.builder().id(CHAT).type("supergroup").build())
                .from(actor)
                .date(date)
                .newChatMember(updated)
                .build();
    }

    private static ChatMember memberState(long id) {
        return ChatMemberMember.builder().user(user(id)).build();
    }

    @Test
    void joiningRecordsJoinTimeFromTheEventDate() {
        when(repository.insertIfAbsent(anyLong(), anyLong(), any(), any())).thenReturn(1);

        recorder.onChatMemberUpdated(event(user(MEMBER), memberState(MEMBER), EVENT_EPOCH_SECONDS));

        verify(repository).insertIfAbsent(eq(CHAT), eq(MEMBER),
                eq(Instant.ofEpochSecond(EVENT_EPOCH_SECONDS)), eq(NOW));
    }

    @Test
    void recordsTheUpdatedMemberNotTheActor() {
        when(repository.insertIfAbsent(anyLong(), anyLong(), any(), any())).thenReturn(1);

        // 管理员（actor）把 MEMBER 的成员状态改了——被记录的人必须是 MEMBER
        recorder.onChatMemberUpdated(event(user(ADMIN), memberState(MEMBER), EVENT_EPOCH_SECONDS));

        verify(repository).insertIfAbsent(eq(CHAT), eq(MEMBER), any(), any());
        verify(repository, never()).insertIfAbsent(anyLong(), eq(ADMIN), any(), any());
    }

    @Test
    void stateChangeOnAnExistingMemberDoesNotOverwriteJoinTime() {
        // 已有行 → INSERT IGNORE 返回 0（晋升/禁言等状态变化）
        when(repository.insertIfAbsent(anyLong(), anyLong(), any(), any())).thenReturn(0);

        ChatMember promoted = ChatMemberAdministrator.builder().user(user(MEMBER)).build();
        recorder.onChatMemberUpdated(event(user(ADMIN), promoted, EVENT_EPOCH_SECONDS));

        // 走的是「不覆盖」的插入，且**绝不**删除行（删除会把入群时间彻底丢掉）
        verify(repository).insertIfAbsent(eq(CHAT), eq(MEMBER), any(), any());
        verify(repository, never()).deleteByChatIdAndUserId(anyLong(), anyLong());
    }

    @Test
    void leavingDeletesTheObservationRow() {
        recorder.onChatMemberUpdated(event(user(MEMBER),
                ChatMemberLeft.builder().user(user(MEMBER)).build(), EVENT_EPOCH_SECONDS));

        verify(repository).deleteByChatIdAndUserId(CHAT, MEMBER);
        verify(repository, never()).insertIfAbsent(anyLong(), anyLong(), any(), any());
    }

    @Test
    void beingKickedAlsoDeletesTheObservationRow() {
        recorder.onChatMemberUpdated(event(user(ADMIN),
                ChatMemberBanned.builder().user(user(MEMBER)).build(), EVENT_EPOCH_SECONDS));

        verify(repository).deleteByChatIdAndUserId(CHAT, MEMBER);
    }

    @Test
    void missingEventDateFallsBackToLocalClockRatherThanDroppingTheEvent() {
        when(repository.insertIfAbsent(anyLong(), anyLong(), any(), any())).thenReturn(1);

        recorder.onChatMemberUpdated(event(user(MEMBER), memberState(MEMBER), null));

        verify(repository).insertIfAbsent(eq(CHAT), eq(MEMBER), eq(NOW), eq(NOW));
    }

    @Test
    void malformedUpdatesAreIgnoredEntirely() {
        recorder.onChatMemberUpdated(null);
        // 缺 chat
        recorder.onChatMemberUpdated(ChatMemberUpdated.builder().from(user(MEMBER)).date(1)
                .newChatMember(memberState(MEMBER)).build());
        // 缺 new_chat_member
        recorder.onChatMemberUpdated(ChatMemberUpdated.builder()
                .chat(Chat.builder().id(CHAT).type("supergroup").build()).date(1).build());

        verifyNoInteractions(repository);
    }

    @Test
    void updateWithoutUserIsIgnored() {
        // 库对 ChatMember.user 标了 @NonNull，builder 根本造不出「无 user」的状态——只能 mock。
        // 该分支仍须保留：它是「字段不全就什么都不做」的守卫（不猜、不写半条记录）。
        ChatMember noUser = mock(ChatMember.class);
        when(noUser.getUser()).thenReturn(null);

        recorder.onChatMemberUpdated(event(user(MEMBER), noUser, EVENT_EPOCH_SECONDS));

        verifyNoInteractions(repository);
    }
}

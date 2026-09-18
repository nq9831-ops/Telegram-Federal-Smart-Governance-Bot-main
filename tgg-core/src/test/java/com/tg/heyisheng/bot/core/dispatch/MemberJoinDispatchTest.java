package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.core.membership.MemberJoinRecorder;
import com.tg.heyisheng.bot.core.middleware.MiddlewareChain;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberUpdated;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@code chat_member} 更新在分发层的接线（模块九 §10.3 入群时长数据源）。
 *
 * <p><b>为什么必须单独测这一层</b>：{@code chat_member} 更新与 callback query 一样
 * <b>没有 message</b>。若不在 {@code relevantMessage} 之前分流，它会走完整个分发链、
 * 匹配不到任何东西、然后被静默丢弃——采集上线了却一条都记不下，而日志上「看不出问题」。
 * 这是本项目反复出现的「静默失效」形态，故用「中间件链零交互」把它钉死。
 */
class MemberJoinDispatchTest {

    private static final long CHAT = -100900999L;
    private static final long MEMBER = 777L;

    private final MiddlewareChain middlewareChain = mock(MiddlewareChain.class);
    private final MemberJoinRecorder recorder = mock(MemberJoinRecorder.class);

    private UpdateDispatcher dispatcherWith(MemberJoinRecorder wired) {
        UpdateDispatcher.Builder builder = UpdateDispatcher.builder()
                .middlewareChain(middlewareChain)
                .commandDispatcher(mock(CommandDispatcher.class));
        if (wired != null) {
            builder.memberJoinRecorder(wired);
        }
        return builder.build();
    }

    private static Update chatMemberUpdate() {
        Update update = new Update();
        update.setUpdateId(1);
        update.setChatMember(ChatMemberUpdated.builder()
                .chat(Chat.builder().id(CHAT).type("supergroup").build())
                .from(User.builder().id(MEMBER).firstName("u").isBot(false).build())
                .date(1_789_000_000)
                .newChatMember(ChatMemberMember.builder()
                        .user(User.builder().id(MEMBER).firstName("u").isBot(false).build())
                        .build())
                .build());
        return update;
    }

    @Test
    void chatMemberUpdateIsRoutedToTheRecorder() throws Exception {
        UpdateDispatcher dispatcher = dispatcherWith(recorder);

        Optional<?> result = dispatcher.dispatch(chatMemberUpdate());

        assertThat(result).isEmpty();
        verify(recorder).onChatMemberUpdated(any());
    }

    @Test
    void chatMemberUpdateNeverReachesTheMiddlewareChain() throws Exception {
        UpdateDispatcher dispatcher = dispatcherWith(recorder);

        dispatcher.dispatch(chatMemberUpdate());

        verifyNoInteractions(middlewareChain);
    }

    @Test
    void chatMemberUpdateIsHarmlessWhenRecorderNotWired() throws Exception {
        UpdateDispatcher dispatcher = dispatcherWith(null);

        // 未装配 = 该能力未启用：既不抛，也不该把这种更新误当普通消息送进链路
        assertThat(dispatcher.dispatch(chatMemberUpdate())).isEmpty();
        verifyNoInteractions(middlewareChain);
    }
}

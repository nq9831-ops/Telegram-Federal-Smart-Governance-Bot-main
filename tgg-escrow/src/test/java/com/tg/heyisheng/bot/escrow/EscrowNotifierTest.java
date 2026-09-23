package com.tg.heyisheng.bot.escrow;

import com.tg.heyisheng.bot.core.notify.Notification;
import com.tg.heyisheng.bot.core.notify.NotificationCategory;
import com.tg.heyisheng.bot.core.notify.NotificationDispatcher;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 进度通知的<b>双通道与开关</b>守卫。
 *
 * <p>它守的是用户明确提出的两点：<b>群内与私聊都要有</b>（缺一个就有一方收不到），
 * <b>但各自可关</b>（运维按部署环境裁剪）。开关写反的后果是静默的：
 * 关掉群内却仍在发（打扰围观者），或"以为开着"却什么都没发（对方永远不知道轮到自己了）。
 */
class EscrowNotifierTest {

    private static final long RECIPIENT = 22L;
    private static final long GROUP = -100L;

    private final NotificationDispatcher privateChannel = mock(NotificationDispatcher.class);
    private final EscrowGroupSender groupChannel = mock(EscrowGroupSender.class);
    private final EscrowProperties properties = new EscrowProperties();
    private final EscrowNotifier notifier = new EscrowNotifier(privateChannel, groupChannel, properties);

    @Test
    void bothChannelsOnByDefaultSendsBoth() {
        notifier.notifyParty(RECIPIENT, GROUP, "买家已托管资金，请交付");

        verify(privateChannel).notify(eq(NotificationCategory.ESCROW), any(Notification.class));
        verify(groupChannel).send(eq(GROUP), anyString());
    }

    @Test
    void groupDisabledSkipsOnlyTheGroupChannel() {
        properties.getNotify().setGroupEnabled(false);

        notifier.notifyParty(RECIPIENT, GROUP, "请验收");

        verify(groupChannel, never()).send(anyLong(), anyString());
        verify(privateChannel).notify(eq(NotificationCategory.ESCROW), any(Notification.class));
    }

    @Test
    void privateDisabledSkipsOnlyThePrivateChannel() {
        properties.getNotify().setPrivateEnabled(false);

        notifier.notifyParty(RECIPIENT, GROUP, "请验收");

        verify(privateChannel, never()).notify(any(), any(Notification.class));
        verify(groupChannel).send(eq(GROUP), anyString());
    }

    @Test
    void bothDisabledSendsNothing() {
        properties.getNotify().setGroupEnabled(false);
        properties.getNotify().setPrivateEnabled(false);

        notifier.notifyParty(RECIPIENT, GROUP, "任何文案");

        verify(privateChannel, never()).notify(any(), any(Notification.class));
        verify(groupChannel, never()).send(anyLong(), anyString());
    }

    @Test
    void withoutGroupContextOnlyPrivateIsUsed() {
        // 私聊里发起的交易没有群上下文——群内通道必须跳过而不是拿 null 去发
        notifier.notifyParty(RECIPIENT, null, "请交付");

        verify(privateChannel).notify(eq(NotificationCategory.ESCROW), any(Notification.class));
        verify(groupChannel, never()).send(anyLong(), anyString());
    }

    @Test
    void missingPrivateChannelDegradesInsteadOfFailing() {
        // 通知模块未装配（如测试切片上下文）：私聊静默跳过，群内照常——不得抛异常拖垮交易链路
        EscrowNotifier withoutPrivate = new EscrowNotifier(null, groupChannel, properties);

        withoutPrivate.notifyParty(RECIPIENT, GROUP, "请交付");

        verify(groupChannel).send(eq(GROUP), anyString());
    }
}
